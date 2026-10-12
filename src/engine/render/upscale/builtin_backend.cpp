#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstdlib>
#include <cstring>

#include "engine/core/log.h"
#include "engine/render/render_util.h"
#include "engine/render/upscale/upscale.h"

namespace pt {
namespace {

// The built-in upscaler: a temporal upscale in one compute shader (shaders/builtin_upscale.comp), the same job as FSR 3, XeSS
// or DLSS (the engine hands every upscaler the jittered render-size colour, depth, motion vectors and a reactive mask) without a
// vendor library, so it runs on any Vulkan GPU. It is the upscaler of the Android build, where the vendor ones are Windows DLLs.
// It keeps two display-size history images, reads last frame's and writes this frame's; the sharpness slider adds a second
// pass that sharpens the result into the output.
constexpr VkFormat kHistoryFormat = VK_FORMAT_R16G16B16A16_SFLOAT;
// descriptor sets in rotation: each dispatch writes its own set, and the GPU may still be reading the sets of the frames in
// flight (two or three of them, two dispatches each)
constexpr uint32_t kRing = 12;
// the history's weight for the new frame when nothing moves: a render image at 1/1.5 the display size has a new sample for a
// display pixel about every ~2 frames, a native render one for every frame, so the full-size render takes a slightly faster one
constexpr float kRestWeightUpscaling = 0.10f;
constexpr float kRestWeightNative = 0.12f;

// Test views (the Android settings panel's Upscaler test view, PT_BUILTIN_VIEW at start): 0 none; 1 a pattern made from the pixel
// position alone; 2 the render image; 3 motion; 4 depth; 5 the history; 6 the reactive mask (all written by the shader straight to
// the output); 7 the output cleared to a colour with no shader; 8 no output at all, so the engine shows the render image through
// its own resolve pass (the picture the other upscalers' fallback gives)
constexpr int kLastView = 8;
std::atomic<int> g_view{-1};
// where the shader writes: 0 the engine's output image itself, 1 an image of its own that is then copied into it, 2 a buffer that
// is then copied into it
std::atomic<int> g_path{-1};
// how the picture is made: 0 a compute shader, 1 a full screen draw (the compute shaders of the first GPU this ran on, an Adreno 740,
// wrote only the top 128 rows of their output and left the rest of a picture black)
std::atomic<int> g_method{-1};

int TestView() {
    int view = g_view.load();
    if (view < 0) {
        const char* env = std::getenv("PT_BUILTIN_VIEW");
        view = env ? std::clamp(std::atoi(env), 0, kLastView) : 0;
        g_view.store(view);
    }
    return view;
}

int OutputPath() {
    int path = g_path.load();
    if (path < 0) {
        const char* env = std::getenv("PT_BUILTIN_PATH");
        path = env ? std::clamp(std::atoi(env), 0, 2) : 0;
        g_path.store(path);
    }
    return path;
}

int Method() {
    int method = g_method.load();
    if (method < 0) {
        const char* env = std::getenv("PT_BUILTIN_METHOD");
        method = env ? std::clamp(std::atoi(env), 0, 1) : 1;
        g_method.store(method);
    }
    return method;
}

// the stages that read the push constants and the first five bindings: the compute shader and the full screen draw
constexpr VkShaderStageFlags kStages = VK_SHADER_STAGE_COMPUTE_BIT | VK_SHADER_STAGE_FRAGMENT_BIT;

// What the shader counts into a buffer of the CPU's (read back three dispatches later): the work groups that ran, the furthest row
// and column of them and how many ran in each 64th of the rows, to see whether a GPU runs a whole dispatch
constexpr uint32_t kBands = 64;
struct Counters {
    uint32_t groups_run;
    uint32_t last_row;
    uint32_t last_column;
    uint32_t spare;
    uint32_t bands[kBands];
};
constexpr uint32_t kCounterSlots = 3;

struct CounterSlot {
    vk::Buffer buffer;
    uint32_t dispatch = 0;
    uint32_t groups_x = 0;
    uint32_t groups_y = 0;
    // the groups the dispatch the slot holds asked for, 0 when it holds none
    uint32_t expected = 0;
};

struct Push {
    uint32_t sizes[4];
    float a[4];
    float b[4];
};
static_assert(sizeof(Push) == 48);

class BuiltinBackend final : public UpscaleBackend {
public:
    explicit BuiltinBackend(vk::Context& ctx) : ctx_(ctx) {}

    ~BuiltinBackend() override {
        Release();
        DestroyPipeline();
    }

    UpscalerKind Kind() const override { return UpscalerKind::Builtin; }

    bool Available(std::string& reason) override {
        if (!EnsurePipeline()) {
            reason = "the built-in upscaler's shader could not be loaded";
            return false;
        }
        reason.clear();
        return true;
    }

    bool Create(VkCommandBuffer cmd, const UpscaleCreate& create) override {
        Release();
        if (!EnsurePipeline()) {
            return false;
        }
        display_ = create.display;
        render_ = create.render;
        const VkImageUsageFlags usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
        const VkExtent3D size{std::max(display_.width, 1u), std::max(display_.height, 1u), 1};
        for (vk::Image& image : history_) {
            if (!ctx_.CreateImage(image, kHistoryFormat, size, usage, 1, 1, VK_IMAGE_ASPECT_COLOR_BIT)) {
                LogError("upscale: built-in history {}x{} could not be created", size.width, size.height);
                Release();
                return false;
            }
            // GENERAL for good: written by one frame's compute pass, sampled by the next
            vk::ImageBarrier(cmd, image.image, VK_IMAGE_ASPECT_COLOR_BIT, VK_PIPELINE_STAGE_2_NONE, 0, VK_IMAGE_LAYOUT_UNDEFINED,
                             VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_READ_BIT | VK_ACCESS_2_SHADER_WRITE_BIT,
                             VK_IMAGE_LAYOUT_GENERAL);
        }
        // the image of the copy output path (OutputPath): written by the shader, copied into the engine's output
        if (!ctx_.CreateImage(scratch_, kHistoryFormat, size, VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT, 1, 1,
                              VK_IMAGE_ASPECT_COLOR_BIT)) {
            LogError("upscale: built-in output image {}x{} could not be created", size.width, size.height);
            Release();
            return false;
        }
        vk::ImageBarrier(cmd, scratch_.image, VK_IMAGE_ASPECT_COLOR_BIT, VK_PIPELINE_STAGE_2_NONE, 0, VK_IMAGE_LAYOUT_UNDEFINED,
                         VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_READ_BIT | VK_ACCESS_2_SHADER_WRITE_BIT,
                         VK_IMAGE_LAYOUT_GENERAL);
        for (CounterSlot& slot : counters_) {
            if (!ctx_.CreateBuffer(slot.buffer, sizeof(Counters), VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true) || !slot.buffer.mapped) {
                LogError("upscale: built-in counters could not be created");
                Release();
                return false;
            }
            std::memset(slot.buffer.mapped, 0, sizeof(Counters));
            vmaFlushAllocation(ctx_.allocator, slot.buffer.allocation, 0, VK_WHOLE_SIZE);
            slot.expected = 0;
        }
        // the buffer of the buffer output path (OutputPath 2): one RGBA16F pixel is two words
        const VkDeviceSize buffer_bytes = VkDeviceSize(size.width) * size.height * 8;
        if (!ctx_.CreateBuffer(out_buffer_, buffer_bytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT, false)) {
            // the shader still needs something bound there; the buffer path is not offered then
            LogWarn("upscale: built-in output buffer of {} MB could not be created; the buffer path is off", buffer_bytes >> 20);
            out_buffer_ = vk::Buffer{};
        }
        write_ = 0;
        next_set_ = 0;
        frames_ = 0;
        last_exposure_ = 0.0f;
        const VkPhysicalDeviceLimits& limits = ctx_.properties.limits;
        LogInfo("upscale: compute limits: groups {} {} {}, group size {} {} {}, invocations {}, shared {} bytes, subgroup size control not used",
                limits.maxComputeWorkGroupCount[0], limits.maxComputeWorkGroupCount[1], limits.maxComputeWorkGroupCount[2],
                limits.maxComputeWorkGroupSize[0], limits.maxComputeWorkGroupSize[1], limits.maxComputeWorkGroupSize[2],
                limits.maxComputeWorkGroupInvocations, limits.maxComputeSharedMemorySize);
        LogInfo("upscale: built-in temporal upscaler {}x{} -> {}x{}", render_.width, render_.height, display_.width, display_.height);
        return true;
    }

    bool Dispatch(const UpscaleDispatch& d) override {
        if (!pipeline_ || !history_[0].image || !d.color.Valid() || !d.depth.Valid() || !d.motion.Valid() || !d.reactive.Valid() || !d.output.Valid()) {
            return false;
        }
        if (d.render.width != render_.width || d.render.height != render_.height || d.display.width != display_.width ||
            d.display.height != display_.height) {
            // the sizes the images were made for; the renderer recreates the backend when they change, so this is a frame in between
            return false;
        }
        const uint32_t read = write_ ^ 1u;
        const int view = TestView();
        const int path_wanted = OutputPath();
        const bool copy = path_wanted == 1 && scratch_.image;
        const bool buffered = path_wanted == 2 && out_buffer_.buffer;
        VkImageView output_view = copy ? scratch_.view : d.output.view;
        const bool sharpen = d.sharpness > 0.001f && view == 0;
        const bool upscaling = d.render.width < d.display.width || d.render.height < d.display.height;

        // last frame's history writes (and the output's reads of it) before this frame's reads and writes
        VkMemoryBarrier2 history_barrier{VK_STRUCTURE_TYPE_MEMORY_BARRIER_2};
        history_barrier.srcStageMask = VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT;
        history_barrier.srcAccessMask = VK_ACCESS_2_SHADER_WRITE_BIT | VK_ACCESS_2_SHADER_READ_BIT;
        history_barrier.dstStageMask = VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT;
        history_barrier.dstAccessMask = VK_ACCESS_2_SHADER_READ_BIT | VK_ACCESS_2_SHADER_WRITE_BIT;
        Barrier(d.cmd, history_barrier);

        Push push{};
        push.sizes[0] = d.render.width;
        push.sizes[1] = d.render.height;
        push.sizes[2] = d.display.width;
        push.sizes[3] = d.display.height;
        push.a[0] = d.jitter.x;
        push.a[1] = d.jitter.y;
        push.a[2] = std::max(d.pre_exposure, 1.0e-12f);
        push.a[3] = upscaling ? kRestWeightUpscaling : kRestWeightNative;
        push.b[0] = std::clamp(d.sharpness, 0.0f, 1.0f);
        push.b[1] = d.reset || last_exposure_ <= 0.0f ? 1.0f : 0.0f;
        // pass 0 resolves, 1 sharpens; +2 when the picture goes to the buffer
        const float to_buffer = buffered ? 2.0f : 0.0f;
        push.b[2] = to_buffer;
        push.b[3] = static_cast<float>(view);

        // the first frames and then about every ten seconds: what this dispatch is given (a picture that goes wrong on a GPU
        // nobody has tried is read from this and the status lines)
        if (frames_ < 3 || frames_ % 600 == 0) {
            LogInfo("upscale: built-in dispatch {}: render {}x{} display {}x{} output {}x{} jitter {:.3f} {:.3f} exposure {:.6f} reset {} view {} "
                    "path {} method {} sharpness {:.2f} layouts color {} depth {} motion {} reactive {} output {}",
                    frames_, d.render.width, d.render.height, d.display.width, d.display.height, d.output.extent.width, d.output.extent.height,
                    d.jitter.x, d.jitter.y, push.a[2], push.b[1] > 0.5 ? 1 : 0, view, copy ? 1 : buffered ? 2 : 0, Method(), d.sharpness,
                    static_cast<int>(d.color.layout), static_cast<int>(d.depth.layout), static_cast<int>(d.motion.layout),
                    static_cast<int>(d.reactive.layout), static_cast<int>(d.output.layout));
        }
        ++frames_;
        if (view == 8) {
            // no upscaled picture: the engine's resolve pass shows the render image itself, stretched (a test of everything after
            // this pass, and of the render passes, with none of this backend's work)
            return false;
        }
        if (view == 7) {
            // the output cleared to orange by the transfer queue's clear, no shader: whether the picture can be written to and read
            // back whole
            VkClearColorValue orange{};
            orange.float32[0] = 0.5f;
            orange.float32[1] = 0.2f;
            orange.float32[2] = 0.0f;
            orange.float32[3] = 1.0f;
            const VkImageSubresourceRange range{VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
            vkCmdClearColorImage(d.cmd, d.output.image, VK_IMAGE_LAYOUT_GENERAL, &orange, 1, &range);
            VkMemoryBarrier2 cleared{VK_STRUCTURE_TYPE_MEMORY_BARRIER_2};
            cleared.srcStageMask = VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT;
            cleared.srcAccessMask = VK_ACCESS_2_TRANSFER_WRITE_BIT;
            cleared.dstStageMask = VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
            cleared.dstAccessMask = VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT;
            Barrier(d.cmd, cleared);
            return true;
        }
        if (Method() == 1) {
            return Draw(d, push, read, sharpen);
        }
        vkCmdBindPipeline(d.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline_);
        const uint32_t groups_x = (d.display.width + 7) / 8;
        const uint32_t groups_y = (d.display.height + 7) / 8;

        // the counters of this dispatch: the slot's last dispatch (three ago) is reported, then the slot is zeroed
        CounterSlot& counter = counters_[(frames_ - 1) % kCounterSlots];
        ReportCounters(counter);
        counter.dispatch = frames_ - 1;
        counter.groups_x = groups_x;
        counter.groups_y = groups_y;
        counter.expected = groups_x * groups_y * (sharpen ? 2u : 1u);

        VkDescriptorSet set = NextSet();
        WriteSet(set, d, history_[read], history_[write_], output_view, counter.buffer.buffer);
        vkCmdBindDescriptorSets(d.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, layout_, 0, 1, &set, 0, nullptr);
        vkCmdPushConstants(d.cmd, layout_, kStages, 0, sizeof(push), &push);
        vkCmdDispatch(d.cmd, groups_x, groups_y, 1);

        if (sharpen) {
            // the resolve wrote the history and the unsharpened output; the sharpen pass reads the history and overwrites the output
            VkMemoryBarrier2 pass_barrier{VK_STRUCTURE_TYPE_MEMORY_BARRIER_2};
            pass_barrier.srcStageMask = VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT;
            pass_barrier.srcAccessMask = VK_ACCESS_2_SHADER_WRITE_BIT;
            pass_barrier.dstStageMask = VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT;
            pass_barrier.dstAccessMask = VK_ACCESS_2_SHADER_READ_BIT | VK_ACCESS_2_SHADER_WRITE_BIT;
            Barrier(d.cmd, pass_barrier);
            push.b[2] = 1.0f + to_buffer;
            VkDescriptorSet sharpen_set = NextSet();
            WriteSet(sharpen_set, d, history_[write_], history_[write_], output_view, counter.buffer.buffer);
            vkCmdBindDescriptorSets(d.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, layout_, 0, 1, &sharpen_set, 0, nullptr);
            vkCmdPushConstants(d.cmd, layout_, kStages, 0, sizeof(push), &push);
            vkCmdDispatch(d.cmd, groups_x, groups_y, 1);
        }

        // the output for the resolve pass that samples it (the target's layout change follows, with its own barrier)
        VkMemoryBarrier2 done{VK_STRUCTURE_TYPE_MEMORY_BARRIER_2};
        done.srcStageMask = VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT;
        done.srcAccessMask = VK_ACCESS_2_SHADER_WRITE_BIT;
        done.dstStageMask = VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
        done.dstAccessMask = VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT;
        Barrier(d.cmd, done);
        if (copy) {
            VkImageCopy region{};
            region.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
            region.dstSubresource = region.srcSubresource;
            region.extent = {d.display.width, d.display.height, 1};
            vkCmdCopyImage(d.cmd, scratch_.image, VK_IMAGE_LAYOUT_GENERAL, d.output.image, VK_IMAGE_LAYOUT_GENERAL, 1, &region);
            VkMemoryBarrier2 copied{VK_STRUCTURE_TYPE_MEMORY_BARRIER_2};
            copied.srcStageMask = VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT;
            copied.srcAccessMask = VK_ACCESS_2_TRANSFER_WRITE_BIT;
            copied.dstStageMask = VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
            copied.dstAccessMask = VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT;
            Barrier(d.cmd, copied);
        }

        if (buffered) {
            VkBufferImageCopy region{};
            region.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
            region.imageExtent = {d.display.width, d.display.height, 1};
            vkCmdCopyBufferToImage(d.cmd, out_buffer_.buffer, d.output.image, VK_IMAGE_LAYOUT_GENERAL, 1, &region);
            VkMemoryBarrier2 copied{VK_STRUCTURE_TYPE_MEMORY_BARRIER_2};
            copied.srcStageMask = VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT;
            copied.srcAccessMask = VK_ACCESS_2_TRANSFER_WRITE_BIT;
            copied.dstStageMask = VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
            copied.dstAccessMask = VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT;
            Barrier(d.cmd, copied);
        }

        write_ = read;
        last_exposure_ = push.a[2];
        return true;
    }

    // The picture as two full screen draws (Method 1): pass 0 resolves into the output and the history, pass 1 (when the sharpness is
    // above zero) sharpens the history over the output. The images stay in GENERAL, which a colour attachment and a texture can both
    // use, so no layout changes are needed.
    bool Draw(const UpscaleDispatch& d, Push push, uint32_t read, bool sharpen) {
        if (d.output.format != kHistoryFormat || !(d.output.usage & VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)) {
            if (!draw_warned_) {
                draw_warned_ = true;
                LogWarn("upscale: the output image (format {}, usage {:#x}) cannot be drawn to; the built-in upscaler's draw method is off", static_cast<int>(d.output.format),
                        static_cast<uint32_t>(d.output.usage));
            }
            return false;
        }
        if (!EnsureDrawPipelines()) {
            return false;
        }
        const VkRect2D area{{0, 0}, d.display};
        VkMemoryBarrier2 all{VK_STRUCTURE_TYPE_MEMORY_BARRIER_2};
        all.srcStageMask = VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
        all.srcAccessMask = VK_ACCESS_2_MEMORY_WRITE_BIT;
        all.dstStageMask = VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
        all.dstAccessMask = VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT;
        // last frame's history writes before this frame reads them, and the engine's passes before the first read of its images
        Barrier(d.cmd, all);

        // the counters buffer fills the unused buffer bindings
        VkBuffer filler = counters_[0].buffer.buffer;
        push.b[2] = 0.0f;
        VkDescriptorSet set = NextSet();
        WriteSet(set, d, history_[read], history_[write_], d.output.view, filler);
        VkRenderingAttachmentInfo colors[2]{};
        for (VkRenderingAttachmentInfo& color : colors) {
            color = {VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO};
            color.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
            color.loadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
            color.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
        }
        colors[0].imageView = d.output.view;
        colors[1].imageView = history_[write_].view;
        BeginDraw(d.cmd, area, colors, 2);
        vkCmdBindPipeline(d.cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, draw_resolve_);
        vkCmdBindDescriptorSets(d.cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, layout_, 0, 1, &set, 0, nullptr);
        vkCmdPushConstants(d.cmd, layout_, kStages, 0, sizeof(push), &push);
        vkCmdDraw(d.cmd, 3, 1, 0, 0);
        vkCmdEndRendering(d.cmd);

        if (sharpen) {
            // the sharpen pass reads the history the resolve just wrote and overwrites the output
            Barrier(d.cmd, all);
            push.b[2] = 1.0f;
            VkDescriptorSet sharpen_set = NextSet();
            WriteSet(sharpen_set, d, history_[write_], history_[write_], d.output.view, filler);
            BeginDraw(d.cmd, area, colors, 1);
            vkCmdBindPipeline(d.cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, draw_sharpen_);
            vkCmdBindDescriptorSets(d.cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, layout_, 0, 1, &sharpen_set, 0, nullptr);
            vkCmdPushConstants(d.cmd, layout_, kStages, 0, sizeof(push), &push);
            vkCmdDraw(d.cmd, 3, 1, 0, 0);
            vkCmdEndRendering(d.cmd);
        }
        // the output for the resolve pass that samples it (the target's layout change follows, with its own barrier)
        Barrier(d.cmd, all);
        write_ = read;
        last_exposure_ = push.a[2];
        return true;
    }

    void Release() override {
        if (ctx_.device) {
            vkDeviceWaitIdle(ctx_.device);
        }
        for (vk::Image& image : history_) {
            if (image.image) {
                ctx_.DestroyImage(image);
            }
            image = vk::Image{};
        }
        if (scratch_.image) {
            ctx_.DestroyImage(scratch_);
        }
        scratch_ = vk::Image{};
        for (CounterSlot& slot : counters_) {
            ctx_.DestroyBuffer(slot.buffer);
            slot = CounterSlot{};
        }
        ctx_.DestroyBuffer(out_buffer_);
        last_exposure_ = 0.0f;
    }

private:
    static void Barrier(VkCommandBuffer cmd, const VkMemoryBarrier2& barrier) {
        VkDependencyInfo dependency{VK_STRUCTURE_TYPE_DEPENDENCY_INFO};
        dependency.memoryBarrierCount = 1;
        dependency.pMemoryBarriers = &barrier;
        vkCmdPipelineBarrier2(cmd, &dependency);
    }

    // dynamic rendering into `count` of the attachments, with the dynamic state every pipeline of the engine expects
    static void BeginDraw(VkCommandBuffer cmd, const VkRect2D& area, const VkRenderingAttachmentInfo* colors, uint32_t count) {
        VkRenderingInfo rendering{VK_STRUCTURE_TYPE_RENDERING_INFO};
        rendering.renderArea = area;
        rendering.layerCount = 1;
        rendering.colorAttachmentCount = count;
        rendering.pColorAttachments = colors;
        vkCmdBeginRendering(cmd, &rendering);
        SetViewport(cmd, area);
        vkCmdSetCullMode(cmd, VK_CULL_MODE_NONE);
        vkCmdSetFrontFace(cmd, VK_FRONT_FACE_COUNTER_CLOCKWISE);
    }

    bool EnsureDrawPipelines() {
        if (draw_resolve_ && draw_sharpen_) {
            return true;
        }
        if (!EnsurePipeline()) {
            return false;
        }
        PipelineDesc desc;
        desc.layout = layout_;
        desc.fragment = "builtin_upscale.frag";
        desc.colors = {kHistoryFormat, kHistoryFormat};
        draw_resolve_ = CreateGraphicsPipeline(ctx_.device, desc);
        desc.fragment = "builtin_upscale_sharpen.frag";
        desc.colors = {kHistoryFormat};
        draw_sharpen_ = CreateGraphicsPipeline(ctx_.device, desc);
        if (!draw_resolve_ || !draw_sharpen_) {
            LogError("upscale: the built-in upscaler's draw pipelines could not be created");
            if (draw_resolve_) {
                vkDestroyPipeline(ctx_.device, draw_resolve_, nullptr);
            }
            if (draw_sharpen_) {
                vkDestroyPipeline(ctx_.device, draw_sharpen_, nullptr);
            }
            draw_resolve_ = VK_NULL_HANDLE;
            draw_sharpen_ = VK_NULL_HANDLE;
            return false;
        }
        LogInfo("upscale: built-in draw pipelines created");
        return true;
    }

    bool EnsurePipeline() {
        if (pipeline_) {
            return true;
        }
        VkDevice device = ctx_.device;
        if (!device) {
            return false;
        }
        VkDescriptorSetLayoutBinding bindings[9]{};
        for (uint32_t i = 0; i < 5; ++i) {
            bindings[i] = {i, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, kStages, nullptr};
        }
        bindings[5] = {5, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
        bindings[6] = {6, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
        bindings[7] = {7, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
        bindings[8] = {8, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
        VkDescriptorSetLayoutCreateInfo set_info{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
        set_info.bindingCount = 9;
        set_info.pBindings = bindings;
        if (!vk::Check(vkCreateDescriptorSetLayout(device, &set_info, nullptr, &set_layout_), "built-in upscaler set layout")) {
            return false;
        }
        const VkDescriptorPoolSize sizes[3] = {{VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 5 * kRing},
                                               {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 2 * kRing},
                                               {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 2 * kRing}};
        VkDescriptorPoolCreateInfo pool_info{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
        pool_info.maxSets = kRing;
        pool_info.poolSizeCount = 3;
        pool_info.pPoolSizes = sizes;
        if (!vk::Check(vkCreateDescriptorPool(device, &pool_info, nullptr, &pool_), "built-in upscaler descriptor pool")) {
            DestroyPipeline();
            return false;
        }
        std::array<VkDescriptorSetLayout, kRing> layouts;
        layouts.fill(set_layout_);
        VkDescriptorSetAllocateInfo alloc{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
        alloc.descriptorPool = pool_;
        alloc.descriptorSetCount = kRing;
        alloc.pSetLayouts = layouts.data();
        if (!vk::Check(vkAllocateDescriptorSets(device, &alloc, sets_.data()), "built-in upscaler descriptor sets")) {
            DestroyPipeline();
            return false;
        }
        const VkPushConstantRange push{kStages, 0, sizeof(Push)};
        VkPipelineLayoutCreateInfo layout_info{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
        layout_info.setLayoutCount = 1;
        layout_info.pSetLayouts = &set_layout_;
        layout_info.pushConstantRangeCount = 1;
        layout_info.pPushConstantRanges = &push;
        if (!vk::Check(vkCreatePipelineLayout(device, &layout_info, nullptr, &layout_), "built-in upscaler pipeline layout")) {
            DestroyPipeline();
            return false;
        }
        VkSamplerCreateInfo sampler_info{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
        sampler_info.addressModeU = sampler_info.addressModeV = sampler_info.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        sampler_info.minLod = 0.0f;
        sampler_info.maxLod = 0.0f;
        sampler_info.magFilter = sampler_info.minFilter = VK_FILTER_NEAREST;
        sampler_info.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
        if (!vk::Check(vkCreateSampler(device, &sampler_info, nullptr, &nearest_), "built-in upscaler sampler")) {
            DestroyPipeline();
            return false;
        }
        sampler_info.magFilter = sampler_info.minFilter = VK_FILTER_LINEAR;
        if (!vk::Check(vkCreateSampler(device, &sampler_info, nullptr, &linear_), "built-in upscaler sampler")) {
            DestroyPipeline();
            return false;
        }
        pipeline_ = CreateComputePipeline(device, layout_, "builtin_upscale.comp");
        if (!pipeline_) {
            LogError("upscale: the built-in upscaler's pipeline could not be created");
            DestroyPipeline();
            return false;
        }
        return true;
    }

    void DestroyPipeline() {
        VkDevice device = ctx_.device;
        if (!device) {
            return;
        }
        if (pipeline_) {
            vkDestroyPipeline(device, pipeline_, nullptr);
        }
        if (draw_resolve_) {
            vkDestroyPipeline(device, draw_resolve_, nullptr);
        }
        if (draw_sharpen_) {
            vkDestroyPipeline(device, draw_sharpen_, nullptr);
        }
        if (layout_) {
            vkDestroyPipelineLayout(device, layout_, nullptr);
        }
        if (pool_) {
            vkDestroyDescriptorPool(device, pool_, nullptr);
        }
        if (set_layout_) {
            vkDestroyDescriptorSetLayout(device, set_layout_, nullptr);
        }
        if (nearest_) {
            vkDestroySampler(device, nearest_, nullptr);
        }
        if (linear_) {
            vkDestroySampler(device, linear_, nullptr);
        }
        pipeline_ = VK_NULL_HANDLE;
        draw_resolve_ = VK_NULL_HANDLE;
        draw_sharpen_ = VK_NULL_HANDLE;
        layout_ = VK_NULL_HANDLE;
        pool_ = VK_NULL_HANDLE;
        set_layout_ = VK_NULL_HANDLE;
        nearest_ = VK_NULL_HANDLE;
        linear_ = VK_NULL_HANDLE;
        sets_.fill(VK_NULL_HANDLE);
    }

    VkDescriptorSet NextSet() {
        VkDescriptorSet set = sets_[next_set_];
        next_set_ = (next_set_ + 1) % kRing;
        return set;
    }

    // binding 4 samples `history`, binding 5 writes `history_out`, binding 6 writes the engine's output image
    void WriteSet(VkDescriptorSet set, const UpscaleDispatch& d, const vk::Image& history, const vk::Image& history_out, VkImageView output,
                  VkBuffer counters) {
        auto layout_of = [](const UpscaleImage& image) {
            return image.layout == VK_IMAGE_LAYOUT_UNDEFINED ? VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL : image.layout;
        };
        VkDescriptorImageInfo images[7]{};
        images[0] = {nearest_, d.color.view, layout_of(d.color)};
        images[1] = {nearest_, d.depth.view, layout_of(d.depth)};
        images[2] = {nearest_, d.motion.view, layout_of(d.motion)};
        images[3] = {nearest_, d.reactive.view, layout_of(d.reactive)};
        images[4] = {linear_, history.view, VK_IMAGE_LAYOUT_GENERAL};
        images[5] = {VK_NULL_HANDLE, history_out.view, VK_IMAGE_LAYOUT_GENERAL};
        images[6] = {VK_NULL_HANDLE, output, VK_IMAGE_LAYOUT_GENERAL};
        VkWriteDescriptorSet writes[9]{};
        for (uint32_t i = 0; i < 7; ++i) {
            writes[i] = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
            writes[i].dstSet = set;
            writes[i].dstBinding = i;
            writes[i].descriptorCount = 1;
            writes[i].descriptorType = i < 5 ? VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER : VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
            writes[i].pImageInfo = &images[i];
        }
        // binding 7 the counters of this dispatch, 8 the buffer path's pixels (the counters stand in when there is none)
        VkDescriptorBufferInfo buffers[2]{};
        buffers[0] = {counters, 0, VK_WHOLE_SIZE};
        buffers[1] = {out_buffer_.buffer ? out_buffer_.buffer : counters, 0, VK_WHOLE_SIZE};
        for (uint32_t i = 0; i < 2; ++i) {
            writes[7 + i] = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
            writes[7 + i].dstSet = set;
            writes[7 + i].dstBinding = 7 + i;
            writes[7 + i].descriptorCount = 1;
            writes[7 + i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            writes[7 + i].pBufferInfo = &buffers[i];
        }
        vkUpdateDescriptorSets(ctx_.device, 9, writes, 0, nullptr);
    }

    // Logs what the shader counted in a dispatch of three ago (first few and every 600th) and zeroes the slot for the next one
    void ReportCounters(CounterSlot& slot) {
        if (!slot.buffer.mapped) {
            return;
        }
        if (slot.expected > 0) {
            vmaInvalidateAllocation(ctx_.allocator, slot.buffer.allocation, 0, VK_WHOLE_SIZE);
            Counters seen;
            std::memcpy(&seen, slot.buffer.mapped, sizeof(seen));
            if (slot.dispatch < 3 || slot.dispatch % 600 == 0) {
                LogInfo("upscale: built-in groups, dispatch {}: {} of {} ran, last row {} of {}, last column {} of {}", slot.dispatch, seen.groups_run,
                        slot.expected, seen.last_row, slot.groups_y, seen.last_column, slot.groups_x);
                for (uint32_t row = 0; row < kBands; row += 16) {
                    LogInfo("upscale: built-in groups per 64th of the rows {:2}..{:2}: {} {} {} {} {} {} {} {} {} {} {} {} {} {} {} {}", row, row + 15,
                            seen.bands[row], seen.bands[row + 1], seen.bands[row + 2], seen.bands[row + 3], seen.bands[row + 4], seen.bands[row + 5],
                            seen.bands[row + 6], seen.bands[row + 7], seen.bands[row + 8], seen.bands[row + 9], seen.bands[row + 10],
                            seen.bands[row + 11], seen.bands[row + 12], seen.bands[row + 13], seen.bands[row + 14], seen.bands[row + 15]);
                }
            }
        }
        std::memset(slot.buffer.mapped, 0, sizeof(Counters));
        vmaFlushAllocation(ctx_.allocator, slot.buffer.allocation, 0, VK_WHOLE_SIZE);
        slot.expected = 0;
    }

    vk::Context& ctx_;
    VkDescriptorSetLayout set_layout_ = VK_NULL_HANDLE;
    VkDescriptorPool pool_ = VK_NULL_HANDLE;
    VkPipelineLayout layout_ = VK_NULL_HANDLE;
    VkPipeline pipeline_ = VK_NULL_HANDLE;
    // Method 1's pipelines: pass 0 (output and history) and pass 1 (the sharpened output)
    VkPipeline draw_resolve_ = VK_NULL_HANDLE;
    VkPipeline draw_sharpen_ = VK_NULL_HANDLE;
    bool draw_warned_ = false;
    VkSampler nearest_ = VK_NULL_HANDLE;
    VkSampler linear_ = VK_NULL_HANDLE;
    std::array<VkDescriptorSet, kRing> sets_{};
    uint32_t next_set_ = 0;
    vk::Image history_[2];
    CounterSlot counters_[kCounterSlots];
    vk::Buffer out_buffer_;
    // the output path 1 image (see OutputPath)
    vk::Image scratch_;
    // the history image this frame writes; the other one holds last frame's result
    uint32_t write_ = 0;
    VkExtent2D render_{};
    VkExtent2D display_{};
    // the exposure of the frame in the history, 0 while there is none
    float last_exposure_ = 0.0f;
    uint32_t frames_ = 0;
};

}

void SetBuiltinUpscalerView(int view) {
    g_view.store(std::clamp(view, 0, kLastView));
}

void SetBuiltinUpscalerMethod(int method) {
    g_method.store(std::clamp(method, 0, 1));
}

void SetBuiltinUpscalerPath(int path) {
    g_path.store(std::clamp(path, 0, 2));
}

std::unique_ptr<UpscaleBackend> CreateBuiltinBackend(vk::Context& ctx) {
    return std::make_unique<BuiltinBackend>(ctx);
}

}
