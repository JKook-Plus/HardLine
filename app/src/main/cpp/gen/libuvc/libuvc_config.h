#ifndef LIBUVC_CONFIG_H
#define LIBUVC_CONFIG_H
#define LIBUVC_VERSION_MAJOR 0
#define LIBUVC_VERSION_MINOR 0
#define LIBUVC_VERSION_PATCH 8
#define LIBUVC_VERSION_STR "0.0.8"
#define LIBUVC_VERSION_INT ((0 << 16) | (0 << 8) | 8)
#define LIBUVC_VERSION_GTE(major, minor, patch) \
  (LIBUVC_VERSION_INT >= (((major) << 16) | ((minor) << 8) | (patch)))
#endif
