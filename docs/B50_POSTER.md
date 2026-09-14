# Experimental B50 poster

Enable **Tools → Settings → Other → Experimental features** to reveal **B50 大图** in Home's actions. The switch defaults to off and persists locally. Day/night poster backgrounds are selected independently of the app theme and persist across restarts.

The poster uses exactly the same chart matching, current-version resolution and B35/B15 rating calculation as Home. It never fabricates scores to fill missing slots. DX stars use the DX score divided by three times the chart note count; unavailable DX score/note totals display as unknown. Each row has five cards, including jackets, difficulty colors, SD/DX, achievement, constant, individual Rating, DX stars and FC/AP/Sync indicators.

Player data never comes from an LXNS player API. Both official WeChat capture and manual-cookie import extract the player's name, trophy, rank and equipped artwork from the authenticated official home page. When the home page omits plate/frame artwork, import follows at most three displayed, read-only collection links and accepts only explicitly equipped items. Optional collection requests time out without failing score import. Unsupported/missing metadata remains unknown, never guessed from an unlocked collection item or another account.

The minimal display snapshot persists in app-private preferences (Android backup is disabled). It excludes cookies, form tokens, friend codes and upstream Rating. A new import replaces the snapshot instead of merging accounts. Existing installations need one new official import to populate metadata that older versions never saved; this is not a prerequisite for generating the local B50. The displayed Rating, its frame and the B35+B15 sum always use the same local calculated value as Home. There is no LXNS token parameter or player-data request in this feature.

Images use Coil's cache with bounded request sizes and four concurrent downloads. Failed-image details list exact labels and public image URLs. Hidden display elements are not fetched or counted as missing. A thumbnail picker offers 经典白天 / 经典夜晚, independently of app theme. Five persisted switches control trophy, nameplate, course rank, friend battle rank and the large background. A hidden nameplate uses translucent white; a hidden large background uses white with dark headings.

The portrait PNG uses the supplied background's original aspect ratio (1500 × 2665), without device-dependent stretching or cropping. The nameplate is a background behind the avatar, trophy and white name strip. The official Rating frame sits above the name, with friend battle rank on its right and course rank to the right of the name. All Rating values use local scores. The download icon asks for confirmation and writes the complete PNG to Pictures/FluentMai through MediaStore. Android 8–9 request storage permission; Android 10+ use a pending MediaStore row and require no broad storage permission. Failed writes remove their incomplete row and show a failure message.

## Asset sources

- User-provided `b50经典白天背景.png` and `b50经典夜晚背景.png` are bundled unchanged as `b50_background_day.png` and `b50_background_night.png`. The earlier generated background is not bundled.
- Jacket images and numbered collection assets: `https://assets2.lxns.net/maimai/{type}/{id}.png` (public images only; no token).
- Equipped icons/plates/frames use the exact official image URLs extracted during import. Official filenames can be hashes, not LXNS numeric IDs; treating them as IDs would give wrong/missing artwork. Only HTTPS images in the official site's `/maimai-mobile/img/` path are allowed, and all query parameters are removed before saving/loading. No captured authentication headers accompany these image downloads.
- Course rank, class rank and FC/AP/Sync artwork: `https://maimai.lxns.net/assets/maimai/`, paths verified against the [LXNS frontend](https://github.com/Lxns-Network/maimai-prober-frontend/tree/main/public/assets/maimai).
- Rating and trophy frames: `https://maimaidx.jp/maimai-mobile/img/rating_base_{color}.png` and `trophy_{color}.png`. Trophy text/color comes from FluentMai's official-page import and is composited into the downloaded frame.
- Visual reference: [maimai official site](https://maimai.sega.jp/).

Game imagery remains the property of its respective rights holders. Remote game art is loaded on demand rather than bundled in the APK.

## Verification

`WahlapPlayerProfileParserTest` covers official display fields, hashed artwork, CSS backgrounds, explicit equipped-item selection, missing profiles and stripping/rejecting sensitive URLs. Official DOM selectors were checked against [maimai.py's parser](https://github.com/TrueRou/maimai.py/blob/main/maimai_py/utils/page_parser.py) and its public sample pages. Collection-marker compatibility still needs validation on a current authenticated device; missing fields are reported rather than fabricated.

`B50PosterTest` covers rating cutoffs, DX-star thresholds and short/full B50 lists. `B50PosterRenderTest` uses Robolectric native graphics to render both backgrounds, 50 cards, long titles, missing FC/FS, an empty profile/list, the original background ratio and hidden-display variants. Preview PNGs are written under `build/test-artifacts/b50-preview/`. Optional `B50_PREVIEW_ASSETS` supplies public image samples for visual QA without requiring CI network access for images.
