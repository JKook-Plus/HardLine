# Third-party notices

HardLine itself is licensed under the GNU General Public License, version 3. See
[LICENSE](LICENSE). The APK also contains the software below, each under its own licence. All of
them allow use in a GPL 3 program.

| Component | Version | Used for | Licence |
|---|---|---|---|
| [libusb](https://libusb.info) | 1.0.30 | USB device access | LGPL 2.1 or later |
| [libuvc](https://github.com/libuvc/libuvc) | 0.0.8, patched | USB Video Class streaming | BSD 3-Clause |
| [Apache Commons Net](https://commons.apache.org/proper/commons-net/) | 3.12.0 | FTP upload | Apache 2.0 |
| [JavaMail for Android](https://eclipse-ee4j.github.io/mail/) | 1.6.7 | E-mail alerts | EPL 2.0, or GPL 2.0 with Classpath Exception |
| [ZXing core](https://github.com/zxing/zxing) | 3.5.4 | QR codes | Apache 2.0 |
| [AndroidX and Jetpack Compose](https://developer.android.com/jetpack) | see `gradle/libs.versions.toml` | User interface | Apache 2.0 |
| [Kotlin and kotlinx.coroutines](https://kotlinlang.org) | see `gradle/libs.versions.toml` | Language and concurrency | Apache 2.0 |

The logo's wordmark is drawn from [Barlow Condensed](https://github.com/jpt/barlow) by Jeremy
Tribby, which is under the SIL Open Font License 1.1. The repository contains the outlines of eight
letters, not the font.

## libusb

Copyright © 2001 Johannes Erdfelt, © 2007-2009 Daniel Drake, © 2010-2012 Peter Stuge,
© 2008-2016 Nathan Hjelm, © 2009-2013 Pete Batard, © 2009-2013 Ludovic Rousseau,
© 2010-2012 Michael Plante, © 2011-2013 Hans de Goede, © 2012-2013 Martin Pieuchot,
© 2012-2013 Toby Gray, © 2013-2018 Chris Dickens, and the other authors named in libusb's
`AUTHORS` file.

libusb is free software under the GNU Lesser General Public License, version 2.1 or any later
version. The full text is at <https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html> and in the
`COPYING` file of the libusb source.

HardLine uses libusb unmodified and ships it as its own shared library, `libusb1.so`, so that you
can replace it. To do that, build libusb 1.0.30 or a compatible version for Android and put the
result in place of `lib/<abi>/libusb1.so` in the APK, or change `scripts/fetch-deps.sh` to fetch
your version and rebuild the app. `scripts/fetch-deps.sh` downloads the exact source HardLine is
built from.

## libuvc

HardLine applies one patch to libuvc 0.0.8. It is in `app/src/main/cpp/patches/`.

```
Software License Agreement (BSD License)

Copyright (C) 2010-2015 Ken Tossell
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions
are met:

 * Redistributions of source code must retain the above copyright
   notice, this list of conditions and the following disclaimer.
 * Redistributions in binary form must reproduce the above
   copyright notice, this list of conditions and the following
   disclaimer in the documentation and/or other materials provided
   with the distribution.
 * Neither the name of the author nor other contributors may be
   used to endorse or promote products derived from this software
   without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN
ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
POSSIBILITY OF SUCH DAMAGE.
```

## Apache-licensed components

Apache Commons Net, ZXing, AndroidX, Jetpack Compose, Kotlin and kotlinx.coroutines are under the
Apache License 2.0. Its text is at <https://www.apache.org/licenses/LICENSE-2.0>.

- Apache Commons Net. Copyright © 2001-2025 The Apache Software Foundation.
- ZXing. Copyright © ZXing authors.
- AndroidX and Jetpack Compose. Copyright © The Android Open Source Project.
- Kotlin and kotlinx.coroutines. Copyright © JetBrains s.r.o. and Kotlin Programming Language
  contributors.

## JavaMail for Android

Copyright © 1997-2021 Oracle and/or its affiliates. JavaMail is offered under the Eclipse Public
License 2.0 or the GNU General Public License, version 2, with the Classpath Exception. HardLine
uses it under the second, <https://www.gnu.org/software/classpath/license.html>, whose exception
allows linking it into a program under another licence. Its source is at
<https://github.com/eclipse-ee4j/mail>.
