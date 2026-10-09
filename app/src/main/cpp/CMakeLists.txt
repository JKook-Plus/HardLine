cmake_minimum_required(VERSION 3.22)
project(usbcam C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(TP ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party)
set(USB ${TP}/libusb-1.0.30)
set(UVC ${TP}/libuvc-0.0.8)

# libusb is LGPL: keep it a separate shared object.
add_library(usb1 SHARED
        ${USB}/libusb/core.c ${USB}/libusb/descriptor.c ${USB}/libusb/hotplug.c ${USB}/libusb/io.c
        ${USB}/libusb/sync.c ${USB}/libusb/strerror.c ${USB}/libusb/os/linux_usbfs.c
        ${USB}/libusb/os/events_posix.c ${USB}/libusb/os/threads_posix.c ${USB}/libusb/os/linux_netlink.c)
target_include_directories(usb1 PUBLIC ${USB}/libusb PRIVATE ${USB}/android ${USB}/libusb/os)
target_compile_options(usb1 PRIVATE -fvisibility=hidden -w)
target_link_libraries(usb1 log)

add_library(usbcam SHARED
        usbcam.cpp
        ${UVC}/src/ctrl.c ${UVC}/src/ctrl-gen.c ${UVC}/src/device.c ${UVC}/src/diag.c
        ${UVC}/src/frame.c ${UVC}/src/init.c ${UVC}/src/misc.c ${UVC}/src/stream.c)
target_include_directories(usbcam PRIVATE ${UVC}/include ${CMAKE_CURRENT_SOURCE_DIR}/gen)
set_source_files_properties(${UVC}/src/ctrl.c ${UVC}/src/ctrl-gen.c ${UVC}/src/device.c ${UVC}/src/diag.c
        ${UVC}/src/frame.c ${UVC}/src/init.c ${UVC}/src/misc.c ${UVC}/src/stream.c
        PROPERTIES COMPILE_FLAGS "-w")
target_link_libraries(usbcam usb1 log)
