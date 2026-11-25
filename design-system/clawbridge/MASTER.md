# ClawBridge Mobile — Master UI Design System

> Audited & synthesized via **UI UX Pro Max (`uipro 2.15.0`)** (`Minimalism & Swiss Style` + `jetpack-compose` stack rules + `Essential Text Truncation` UX rules), tailored to **ChatGPT Mobile Dark Mode** design language.

## 1. Visual Direction
- **Aesthetic**: Calm, disciplined, high-craft dark neutral workspace inspired by ChatGPT Mobile.
- **Strictly Forbidden**: Purple-blue gradients, neon cyan/magenta glows, cheap glassmorphism, emoji as structural icons, per-screen ad-hoc colors.
- **Core Principles**: Generous whitespace, 1dp hairline borders instead of heavy shadows, crisp vertical typography hierarchy, zero essential-text truncation, small-screen safe (`360dp` minimum width).

## 2. Color Tokens (Dark Theme Single Source of Truth)
| Role | Token | Hex | Usage |
| :--- | :--- | :--- | :--- |
| Canvas Background | `ClawColors.Canvas` | `#141414` | Scaffold & root screen backdrop |
| Card Surface | `ClawColors.Surface` | `#1E1E1E` | Primary cards, bottom bar, top bar, sheets |
| Elevated Surface | `ClawColors.SurfaceElevated` | `#282828` | User chat bubble, input fields, chips, nested pills |
| Code Surface | `ClawColors.SurfaceCode` | `#111111` | Code blocks & command previews |
| Hairline Border | `ClawColors.Border` | `#2E2E2E` | `1.dp` card outlines & row dividers |
| Strong Border | `ClawColors.BorderStrong` | `#424242` | Active chips, focused fields |
| Primary CTA | `ClawColors.Primary` | `#ECECEC` | High-contrast restrained primary button / active indicator (`onPrimary = #141414`) |
| Brand Accent | `ClawColors.Accent` | `#2EB88A` | Restrained ChatGPT-style muted emerald-teal for progress bars & active highlights |
| Primary Text | `ClawColors.TextPrimary` | `#ECECEC` | Headings, primary copy (~13.6:1 contrast on `#1E1E1E`) |
| Secondary Text | `ClawColors.TextSecondary` | `#9B9B9B` | Subtitles, metadata (~6.1:1 contrast on `#1E1E1E`) |
| Muted Text | `ClawColors.TextMuted` | `#737373` | Timestamps, placeholders |
| Status Online | `ClawColors.Success` | `#4ADE80` | Soft green dot/text (`tint = #172A21`, `border = #244736`) |
| Status Warning | `ClawColors.Warning` | `#EAB308` | Soft amber dot/text (`tint = #2B2415`, `border = #4A3D20`) |
| Status Error | `ClawColors.Error` | `#F07178` | Soft coral-red (`tint = #2D1B1E`, `border = #4F2A30`) |
| Codex Badge | `ClawColors.BadgeCodexBg` | `#242830` | Text `#D4DCE8`, Border `#384152` |
| AGY Badge | `ClawColors.BadgeAgyBg` | `#182926` | Text `#7DD3C0`, Border `#26453F` |

## 3. Typography Hierarchy (`ClawTypography`)
- `headlineSmall`: `20.sp` SemiBold, `lineHeight = 26.sp` — Hero connection status
- `titleMedium`: `16.sp` SemiBold, `lineHeight = 22.sp` — Card titles & dialog titles
- `titleSmall`: `14.sp` SemiBold, `lineHeight = 20.sp` — Session titles & subsection labels
- `bodyLarge`: `15.sp` Normal, `lineHeight = 22.sp` — Chat message prose
- `bodyMedium`: `13.5.sp` Normal, `lineHeight = 19.sp` — Card descriptions & preview copy
- `bodySmall`: `12.sp` Normal, `lineHeight = 17.sp` — Subtitles, countdowns, captions
- `labelMedium`: `12.sp` Medium, `lineHeight = 16.sp` — Buttons, chips, segmented tabs
- `labelSmall`: `11.sp` Medium, `lineHeight = 14.sp` — Badges, timestamps, monospace values

## 4. Spacing, Shape & Icon Standards (`ClawDimens`)
- **4/8dp Rhythm**: `xs = 4.dp`, `sm = 8.dp`, `md = 12.dp`, `lg = 16.dp`, `xl = 20.dp`, `xxl = 24.dp`
- **Screen Gutter**: `16.dp` horizontal, `16.dp` vertical section rhythm
- **Card Padding**: `16.dp`, `1.dp` border (`ClawColors.Border`), zero heavy elevation shadows
- **Corner Radii**: `Card = 16.dp`, `Nested = 12.dp`, `Pill/Bubble = 20.dp`, `Badge = 6.dp`
- **Icons**: Material Rounded/Outlined vectors (`14/16/18/22.dp`), zero emojis as UI icons, touch targets `>= 44.dp` (48dp for icon buttons).

## 5. Page Audit Fix Checklist
1. **Home**: Stack quota card title & subtitle vertically (`Column`) to eliminate overlap; place full Relay URL on its own wrapping row (zero ellipsis truncation); align client section headers cleanly.
2. **Sessions**: Horizontally scrollable filter chips for `360dp` widths; refined 1dp bordered cards with 44dp+ pin target and aligned status/time footer.
3. **Chat Detail**: Clean ChatGPT-style neutral user bubbles (`#282828`) & structured assistant cards (`#1E1E1E` + `1.dp` hairline border); scrollable sub-tabs; rounded pill composer with clean vector icons for Image/File upload & quick commands.
4. **Pending Approvals & PIN**: Calm dark card with subtle semantic border (no muddy red/orange flood fill); `weight(1f)` project label so risk badge never overlaps.
5. **Settings**: Uniform card sections with delicate `1.dp` internal dividers, adaptive non-truncating buttons, and clear visual grouping.
