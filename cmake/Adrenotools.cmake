# Android only: libadrenotools lets the person pick a custom Vulkan driver (a Turnip/Mesa build for their Adreno GPU)
# instead of the phone's own (src/engine/platform/android_platform.cpp, docs/android.md). It opens a dlopen-like
# handle for libvulkan.so whose driver is redirected to a chosen file, through a small hook library loaded into an
# isolated linker namespace (liblinkernsbypass does the namespace trick). Both are fetched as pinned tarballs with a
# checked SHA-256, so the build is reproducible.
enable_language(C)  # adrenotools' own project() needs C before the NDK toolchain wires it up lazily

set(PT_ADRENOTOOLS_COMMIT 8fae8ce254dfc1344527e05301e43f37dea2df80)
set(PT_ADRENOTOOLS_SHA256 ceffce971676d4cfdf348a082df06fc92a1dca6d95bea892a480d63f200961cb)
set(PT_LINKERNSBYPASS_COMMIT aa3975893d83ef1bc84c321ec60c65fbf1287887)
set(PT_LINKERNSBYPASS_SHA256 da1128c8aa771c4d24766b53a47822d0717baa5c536ca8491220402942b80638)

set(_root "${CMAKE_BINARY_DIR}/_deps/adrenotools")
set(_src "${_root}/libadrenotools-${PT_ADRENOTOOLS_COMMIT}")
set(_stamp "${PT_ADRENOTOOLS_COMMIT} ${PT_LINKERNSBYPASS_COMMIT}\n")
set(_stamped "")
if(EXISTS "${_src}/pt.stamp")
  file(READ "${_src}/pt.stamp" _stamped)
endif()
if(NOT _stamped STREQUAL _stamp)
  file(REMOVE_RECURSE "${_src}")
  foreach(item "libadrenotools;${PT_ADRENOTOOLS_COMMIT};${PT_ADRENOTOOLS_SHA256}" "liblinkernsbypass;${PT_LINKERNSBYPASS_COMMIT};${PT_LINKERNSBYPASS_SHA256}")
    list(GET item 0 name)
    list(GET item 1 commit)
    list(GET item 2 sha256)
    set(archive "${_root}/${name}-${commit}.tar.gz")
    file(DOWNLOAD "https://github.com/bylaws/${name}/archive/${commit}.tar.gz" "${archive}"
         EXPECTED_HASH SHA256=${sha256} TLS_VERIFY ON)
    file(ARCHIVE_EXTRACT INPUT "${archive}" DESTINATION "${_root}")
  endforeach()
  # liblinkernsbypass is adrenotools' own submodule, expected under lib/linkernsbypass
  file(REMOVE_RECURSE "${_src}/lib/linkernsbypass")
  file(RENAME "${_root}/liblinkernsbypass-${PT_LINKERNSBYPASS_COMMIT}" "${_src}/lib/linkernsbypass")
  file(WRITE "${_src}/pt.stamp" "${_stamp}")
endif()

# the "adrenotools" static library, plus the hook shared libraries (main_hook, hook_impl) it loads by name from the app's
# native library directory, so they ship in the APK beside libmain.so (android/app/build.gradle builds them as targets)
add_subdirectory("${_src}" "${CMAKE_BINARY_DIR}/_deps/adrenotools-build" EXCLUDE_FROM_ALL)
