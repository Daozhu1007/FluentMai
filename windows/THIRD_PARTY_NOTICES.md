# Windows third-party notices

This file records the principal direct Windows dependencies. It is not a substitute for the license texts and notices required by every dependency included in a particular packaged artifact.

## PyQt6 and Qt 6

PyQt6 is distributed by Riverbank Computing under the GNU GPL v3 and a commercial license. The bundled Qt runtime has its own GPL/LGPL/commercial terms. A Windows distributor must select and comply with a valid licensing path and include all required license materials.

## PyQt6-Fluent-Widgets

PyQt6-Fluent-Widgets is declared by its package metadata as GPLv3.

## requests

requests is licensed under the Apache License 2.0.

## Beautiful Soup

beautifulsoup4 is licensed under the MIT License.

## mitmproxy

The FluentMai local Wahlap capture helper uses portions of mitmproxy. mitmproxy is licensed under the MIT License. FluentMai configures the helper to listen only on loopback and to intercept only the required Wahlap host; this product policy is separate from mitmproxy's license terms.

## PyInstaller

PyInstaller is licensed under GPL-2.0-or-later with its exception for building and distributing packaged applications. PyInstaller's license does not replace the licenses of the application or bundled libraries.

## Python and transitive packages

The Python runtime and transitive packages bundled by PyInstaller retain their respective licenses and notice requirements. Before a public Windows Release, generate an inventory from the clean release environment, include the corresponding license texts, and compare that inventory with the final portable archive.

## Release gate

The FluentMai product repository currently has no declared project-level `LICENSE`, and no GPL/commercial-license compliance choice has been recorded for the Windows UI stack. Therefore this notice is sufficient only for development CI evidence; it does not make the current Windows artifact ready for public release.
