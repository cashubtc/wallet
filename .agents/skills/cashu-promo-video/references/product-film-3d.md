# The 3D product film

A second format, separate from the house films. The maintainer asked for it
("clean minimal, apple style, no text, just the image of the phone and our
elements") and confirmed every deviation on this page. It doesn't loosen the
house rules for the 1:1 films. The first one is the mint transfer film. Its
Blender scripts live outside this repo; this page is what it learned.

## Contents

- [What it is](#what-it-is)
- [How it's built](#how-its-built)
- [The camera](#the-camera)
- [Rendering traps](#rendering-traps)
- [Two named mints](#two-named-mints)
- [The end card on 16:9](#the-end-card-on-169)

## What it is

- 1920 × 1080, 30 fps, silent, dark, about 19 s. No captions.
- One 3D phone, modelled to iPhone 17 Pro proportions, over the app's ASCII
  field, rendered full frame and sharp.
- **Still only the real app on screen.** The phone's display is the Simulator
  take, one take frame per film frame. Nothing on the screen is drawn.
- One unbroken camera, no cuts, then the house end card.

## How it's built

1. **Record the take** as for a house film ([capture.md](capture.md)), and
   retime it by decode order.
2. **Lay it onto the film's timeline** as plays and holds, and write one
   screen frame per film frame. Convert the colour on the way: `recordVideo`
   tags Display P3, and Blender reads the frames as sRGB.
3. **Render the phone in Blender** (EEVEE, 64 samples, motion blur) on a
   transparent film, so the field can go under it in the encode.
4. **Render a screen-only pass** (see the tone-curve trap below).
5. **Encode** the field, the phone pass, the screen pass and the end card.

Typing pace and hold checks work exactly as in [cutting.md](cutting.md).

## The camera

Settled over several rounds with the maintainer:

- **Hold, then glide.** A few moves of about 1.5 s each, with a soft start
  and a long, soft landing, and the camera still between them. A slow drift
  across the whole film read as "a bit too slow". Ten-frame snap zooms onto
  each button read as "too many jumps… too fast and crazy".
- **Every move has a reason.** In to the top of the phone as the amount is
  typed, out a little for Review, back in on the result.
- **Yaw and pitch only turn one way.** Swinging back read as "too much back
  and forth". The distance may go out and in again when the story asks.
- **The phone's top edge stays in frame,** with its corners, the status bar
  and the island. A close-up cropped through the top read as cut off.
- **Show what's being confirmed before the result.** The pull-back lands on
  Review's From / To / Network fee / Total and holds there at rest; only then
  do Transferring… and the check play.
- **No button close-ups and no press emphasis.** "We don't need to show the
  buttons being pressed."
- **No depth of field.** Shallow focus smeared the screen's bright band into
  a glow behind the phone.

## Rendering traps

- **Never put UI through a filmic or neutral tone curve.** Khronos PBR
  Neutral suits the aluminium, but its toe subtracts up to 0.04 linear from
  dark values. The app's 11% white fills came out near black, and white
  capped near 239. Render the screen a second time: its emission only, under
  the Standard view transform, with every other mesh a holdout. Laid over the
  beauty pass, it brings the app's pixels back 1:1.
- **The display barely reflects.** At full glass specular the softboxes laid
  grey sheets across the UI: keep the screen's specular near 0. The Dynamic
  Island is a matte black hole, not glass.
- **Light every edge.** Two rims, a top strip and a bottom strip. Without the
  bottom strip the lower edge sinks into the black and the phone reads as cut
  off.
- **Judge edges on a full render.** Half-size, 8-sample previews draw the
  phone's thin rims dashed and jagged, worst under motion blur. At 64 samples
  they're clean.

## Two named mints

A transfer needs two mints whose names and URLs read well on screen. Run two
FakeWallet mints (here "Alpine Mint" holding the funds and "Harbor Mint"
empty) behind a loopback-only proxy on port 80 that routes by Host header.
The app then shows `http://alpine.lvh.me` and `http://harbor.lvh.me`, since
`*.lvh.me` resolves to 127.0.0.1. On macOS an unprivileged process can bind
0.0.0.0:80 but not 127.0.0.1:80, so the proxy listens on all interfaces and
drops anything that isn't from loopback.

## The end card on 16:9

- **The house card, centred on the wide frame:** the same face, sizes, rise
  and stagger, and the official badges at the same height (`FilmKit`'s
  `endCardCards`).
- **No dark pocket under the type.** Over this film's full-strength field
  the house card's quiet pocket read as "a black box". Dim the whole field
  evenly instead, to about 30%, as the card comes in.
- **Let the phone go first.** Fade the phone out over 0.5 s after about
  1.2 s on the result, and start the card once it's gone. Overlapping the
  two sets the wordmark over a ghost of the screen.
