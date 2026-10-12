#include <algorithm>
#include <array>
#include <cmath>
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
        const VkImageUsageFlags usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
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
        write_ = 0;
        next_set_ = 0;
        last_exposure_ = 0.0f;
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
        const bool sharpen = d.sharpness > 0.001f;
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
        push.b[2] = 0.0f;
        push.b[3] = 0.0f;

        vkCmdBindPipeline(d.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline_);
        const uint32_t groups_x = (d.display.width + 7) / 8;
        const uint32_t groups_y = (d.display.height + 7) / 8;

        VkDescriptorSet set = NextSet();
        WriteSet(set, d, history_[read], history_[write_]);
        vkCmdBindDescriptorSets(d.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, layout_, 0, 1, &set, 0, nullptr);
        vkCmdPushConstants(d.cmd, layout_, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(push), &push);
        vkCmdDispatch(d.cmd, groups_x, groups_y, 1);

        if (sharpen) {
            // the resolve wrote the history and the unsharpened output; the sharpen pass reads the history and overwrites the output
            VkMemoryBarrier2 pass_barrier{VK_STRUCTURE_TYPE_MEMORY_BARRIER_2};
            pass_barrier.srcStageMask = VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT;
            pass_barrier.srcAccessMask = VK_ACCESS_2_SHADER_WRITE_BIT;
            pass_barrier.dstStageMask = VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT;
            pass_barrier.dstAccessMask = VK_ACCESS_2_SHADER_READ_BIT | VK_ACCESS_2_SHADER_WRITE_BIT;
            Barrier(d.cmd, pass_barrier);
            push.b[2] = 1.0f;
            VkDescriptorSet sharpen_set = NextSet();
            WriteSet(sharpen_set, d, history_[write_], history_[write_]);
            vkCmdBindDescriptorSets(d.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, layout_, 0, 1, &sharpen_set, 0, nullptr);
            vkCmdPushConstants(d.cmd, layout_, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(push), &push);
            vkCmdDispatch(d.cmd, groups_x, groups_y, 1);
        }

        // the output for the resolve pass that samples it (the target's layout change follows, with its own barrier)
        VkMemoryBarrier2 done{VK_STRUCTURE_TYPE_MEMORY_BARRIER_2};
        done.srcStageMask = VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT;
        done.srcAccessMask = VK_ACCESS_2_SHADER_WRITE_BIT;
        done.dstStageMask = VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
        done.dstAccessMask = VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT;
        Barrier(d.cmd, done);

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
        last_exposure_ = 0.0f;
    }

private:
    static void Barrier(VkCommandBuffer cmd, const VkMemoryBarrier2& barrier) {
        VkDependencyInfo dependency{VK_STRUCTURE_TYPE_DEPENDENCY_INFO};
        dependency.memoryBarrierCount = 1;
        dependency.pMemoryBarriers = &barrier;
        vkCmdPipelineBarrier2(cmd, &dependency);
    }

    bool EnsurePipeline() {
        if (pipeline_) {
            return true;
        }
        VkDevice device = ctx_.device;
        if (!device) {
            return false;
        }
        VkDescriptorSetLayoutBinding bindings[7]{};
        for (uint32_t i = 0; i < 5; ++i) {
            bindings[i] = {i, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
        }
        bindings[5] = {5, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
        bindings[6] = {6, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
        VkDescriptorSetLayoutCreateInfo set_info{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
        set_info.bindingCount = 7;
        set_info.pBindings = bindings;
        if (!vk::Check(vkCreateDescriptorSetLayout(device, &set_info, nullptr, &set_layout_), "built-in upscaler set layout")) {
            return false;
        }
        const VkDescriptorPoolSize sizes[2] = {{VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 5 * kRing}, {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 2 * kRing}};
        VkDescriptorPoolCreateInfo pool_info{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
        pool_info.maxSets = kRing;
        pool_info.poolSizeCount = 2;
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
        const VkPushConstantRange push{VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(Push)};
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
    void WriteSet(VkDescriptorSet set, const UpscaleDispatch& d, const vk::Image& history, const vk::Image& history_out) {
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
        images[6] = {VK_NULL_HANDLE, d.output.view, VK_IMAGE_LAYOUT_GENERAL};
        VkWriteDescriptorSet writes[7]{};
        for (uint32_t i = 0; i < 7; ++i) {
            writes[i] = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
            writes[i].dstSet = set;
            writes[i].dstBinding = i;
            writes[i].descriptorCount = 1;
            writes[i].descriptorType = i < 5 ? VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER : VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
            writes[i].pImageInfo = &images[i];
        }
        vkUpdateDescriptorSets(ctx_.device, 7, writes, 0, nullptr);
    }

    vk::Context& ctx_;
    VkDescriptorSetLayout set_layout_ = VK_NULL_HANDLE;
    VkDescriptorPool pool_ = VK_NULL_HANDLE;
    VkPipelineLayout layout_ = VK_NULL_HANDLE;
    VkPipeline pipeline_ = VK_NULL_HANDLE;
    VkSampler nearest_ = VK_NULL_HANDLE;
    VkSampler linear_ = VK_NULL_HANDLE;
    std::array<VkDescriptorSet, kRing> sets_{};
    uint32_t next_set_ = 0;
    vk::Image history_[2];
    // the history image this frame writes; the other one holds last frame's result
    uint32_t write_ = 0;
    VkExtent2D render_{};
    VkExtent2D display_{};
    // the exposure of the frame in the history, 0 while there is none
    float last_exposure_ = 0.0f;
};

}

std::unique_ptr<UpscaleBackend> CreateBuiltinBackend(vk::Context& ctx) {
    return std::make_unique<BuiltinBackend>(ctx);
}

}
