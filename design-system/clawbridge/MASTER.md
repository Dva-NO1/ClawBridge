# ClawBridge Mobile — Master UI Design System (v1.9.0 Aurora Breeze)

> Synthesized with **UI UX Pro Max** (`jetpack-compose` stack guidelines + `color-accessible-pairs` dark contrast rules), upgraded in **v1.9.0** from monochrome charcoal to a **Youthful Luminous Aurora-Slate** aesthetic.

## 1. Visual Direction
- **Aesthetic**: Fresh, luminous, youthful **Aurora Breeze Dark** — starry midnight-indigo surfaces paired with cheerful periwinkle-sky primary buttons, spring mint-teal highlights, and warm cobalt user chat bubbles.
- **Craft Rules**: Crisp `1.dp` tinted slate borders, refined subtle 2-stop linear gradients reserved only for hero accents & primary creation CTAs, Material Rounded vector icons (zero emojis as structural icons), and small-screen safety (`360dp` minimum width).

## 2. Color Tokens (`ClawColors` — Single Source of Truth)
| Role | Token | Hex | Usage |
| :--- | :--- | :--- | :--- |
| Canvas Background | `ClawColors.Canvas` | `#0E1320` | Root scaffold backdrop (deep midnight sky) |
| Card Surface | `ClawColors.Surface` | `#161E31` | Primary cards, top/bottom bars, dialogs |
| Elevated Surface | `ClawColors.SurfaceElevated` | `#202B44` | Input fields, unselected pills, nested surfaces |
| Code Surface | `ClawColors.SurfaceCode` | `#0A0E18` | Code blocks & command previews (`header = #131A2B`) |
| Hairline Border | `ClawColors.Border` | `#293757` | `1.dp` card outlines & dividers |
| Strong Border | `ClawColors.BorderStrong` | `#3F5585` | Focused inputs, active subtle pills |
| Primary CTA | `ClawColors.Primary` | `#6384FF` | Periwinkle-sky primary buttons & active tabs (`onPrimary = #FFFFFF`) |
| Primary Soft | `ClawColors.PrimarySoft` | `#1D2B53` | Navigation bar active pill & tinted chips (`border = #3B5598`) |
| Hero Gradient | `HeroGradientStart/End` | `#5B8CFF` → `#8B6EFF` | New Project CTA button & hero status accent bar |
| Brand Accent | `ClawColors.Accent` | `#2DD4BF` | Spring mint-teal for live TPS, model icon, latency & healthy quota |
| Secondary Accent | `ClawColors.SecondaryAccent` | `#A78BFA` | Soft lavender for subagents & secondary highlights |
| User Chat Bubble | `ClawColors.UserBubbleBg` | `#234177` | Youthful cobalt user message bubble (`border = #3B64AD`) |
| Primary Text | `ClawColors.TextPrimary` | `#F4F7FE` | Headings & prose (~14.8:1 contrast on `#161E31`) |
| Secondary Text | `ClawColors.TextSecondary` | `#A6B4D0` | Subtitles & metadata (~7.1:1 contrast on `#161E31`) |
| Muted Text | `ClawColors.TextMuted` | `#7384A6` | Timestamps & captions (~4.6:1 contrast) |
| Status Online | `ClawColors.Success` | `#34D399` | Mint-emerald (`bg = #112C27`, `border = #21574B`) |
| Status Warning | `ClawColors.Warning` | `#FBBF24` | Warm amber (`bg = #2C2312`, `border = #57441E`) |
| Status Error | `ClawColors.Error` | `#FB7185` | Youthful rose-coral (`bg = #301824`, `border = #5E2B42`) |
| Codex Badge | `ClawColors.BadgeCodexBg` | `#1E2749` | Text `#C7D2FE`, Border `#485CA8` |
| AGY Badge | `ClawColors.BadgeAgyBg` | `#113231` | Text `#6EE7B7`, Border `#256B65` |

## 3. Typography & Spacing (`ClawTypography` & `ClawDimens`)
- **4/8dp Rhythm**: `xs = 4.dp`, `sm = 8.dp`, `md = 12.dp`, `lg = 16.dp`, `xl = 20.dp`
- **Corner Radii**: `Card = 16.dp`, `Nested = 12.dp`, `Bubble = 18.dp`, `Pill = 22.dp`, `Badge = 6.dp`
- **Chat Transient Banners**: All top feedback/upload/download completion banners auto-dismiss after **3.0 seconds** (`3000L`) while keeping manual close (`×`) available.
