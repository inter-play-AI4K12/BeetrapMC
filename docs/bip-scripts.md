# Bip scripts — Introduction, Tutorial (games 1–4), Actual Game (game 5)

Every word Bip says lives in `BeeCuriousService/src/beecurious_service/activities.py` (one table per
activity). The Java states in `src/main/java/beetrap/btfmc/state/` only decide *when* each line
plays and wait for the player. Bip's stance per activity lives in
`BeeCuriousService/.../agents/bip_v4/prompts.py`.

## What Bip knows and sees

- **Knowledge:** a "how the garden works" sheet (data panel rows and what they mean, pollinating,
  buds/numbers/circle/pollen, the diversity score, clocks, the big idea, how to break the bubble).
- **Stance:** intro + games 1–2 = *teacher buddy* (explains everything visible, saves the big "why"
  for game 3, never answers "I don't know"); game 3 = *guide* (ask first, then explain);
  game 4 = *bubble breaker* (hint, then show); game 5 = *garden buddy* (player leads).
- **Sees everything:** each chat message carries the live game state: stage, diversity score
  (+ start value and game 4 target), hive position, circle radius, ranking method, the latest bud
  ranking, whether the clocks were given, the player's **data panel** (last flower aimed at), and
  every flower with all its data panel numbers. Improvised lines get a summary of the latest
  heartbeat too.
- **Tone:** friendly older kid; one short sentence, at most one "!", no classroom phrasing. A test
  (`tests/test_activities.py`) checks every scripted line.

## Introduction (`intro`, `BipIntroState`) — right after `/game new 3` and consent

hello (flies to player) → asks the player's name (nudges if quiet) → "This garden grows flowers based
on what you pick" → "Kinda like how YouTube or TikTok pick videos for you" → "We'll find out how
that works, and how to stay in charge of it" → the plan (explore, pollinate, crack the secret, save
it) → question time (~15s of quiet ends it) → "Alright, let's go!"

## Game 1 — Observe the flowers (`observe`, `ObserveBipState`)

1. Welcome ("Stage one!"). Name exchange only if the intro was skipped.
2. Flies to the nearest flower: "Every flower here has its own secret info."
3. "Point your crosshair at a flower…" → waits until they do (nudges at ~20s/~40s, flying back to
   the flower; continues after ~1 min).
4. Explains the data panel row by row **using that flower's real numbers**: what the panel is,
   Color, Smell strength, Nectar sweetness, Water needed, Sunlight, Flower number, "every flower has
   its own mix of numbers".
5. "Go find a flower that looks interesting. Point at it and tell me!" → Bip reacts to what they
   found using its real numbers and asks what made them pick it. **Quiet for 1 minute → Bip flies to
   a standout flower: "Psst, what about this {color} one? Its {trait} is {high/low}."** (twice max)
6. "Now find one that's totally different from that one" → compares the two (suggests a very
   different flower after a quiet minute).
7. Question time (~20s of quiet ends it) → "Now you know what's in the garden" → "Next up, we get to
   pollinate them." No beehive item in this stage.

## Game 2 — The filter bubble effect (`pollinate`, `FilterBubbleBip*State`)

1. "Stage two! You already know how to read a flower's data panel." → "Now let's do what bees do
   best. Pollinate!"
2. Bip teaches pollinating himself (no picture): flies to a flower, "point your crosshair at a
   flower, like this {color} one" → waits until they aim → "See the little hive in slot five? Press 5"
   → "Now, holding the hive, right-click the flower!" (reminders at ~20s/~40s if they don't).
3. First pollination, narrated **in the order it happens** (the animation waits for Bip before the
   bloom): "You did it! Now watch the big beehive" → hive flew over → baby buds → each bud got a
   number, #1 is the hive's top pick → the glowing circle → pollen flying in → bloom: "they look a
   lot like your flower" → a flower faded → "Why do you think it faded?" (hint, the full answer is
   game 3's) → the diversity score at the top: what it means, it just went down → hands over the
   clocks → "Try rewinding" → "let's pollinate another flower".
4. Round 2: "Try a flower that looks totally different" → buds → "the new ones look like what you
   picked before" → "Check the diversity score. It keeps dropping…". Later rounds: short lines.
5. Diversity below half: "So many kinds of flowers are gone" → "The diversity score dropped a ton!"
   → "Why did that happen? I'll show you in the next stage." → "Let's go!"

## Game 3 — How the garden recommends (`recommend`, `RecommendBip*State`)

"Stage three! Remember how the flowers faded? Let's crack the secret." → to the beehive → why are
some flowers close together? (answer: similar data panel numbers) → round 1: what do the bud
numbers rank, what goes into the hive → clocks → round 2: the circle, why that flower died → round
3: Bip-led quiz (asks aloud, multiple-choice screen, reacts) → ending: why did the garden lose its
colors → hive = profile → recommender system → "that's called a filter bubble" → "Next, let's figure
out how to save the garden."

## Game 4 — Break the filter bubble (`diversify`, `DiversifyBip*State`)

Follows the Diversification rows of "Make Bip Great Again": "Last stage! This garden's already stuck
in a bubble" → all the flowers are kinda the same → recall why → mission: diversity above {target},
the bar shows progress → hint: the pollen circle → pollinate → "press B near the circle" (nudges;
Bip grows it for them after ~2 min) → "the circle got bigger!" → at radius 5 a lever pops up, Bip
flies to it: "What do you think it does? Try flicking it." → lever on: "the hive picks buds that are
different from what you like" → bloom waits for the lever (~1 min max) → "Look at the diversity
score now!" → "Why do you think it went up?" → explains → keep going / "so close" → success: "You
broke the bubble and saved the garden" → "Mix up what you watch to keep your feed colorful" →
"That's the whole tutorial."

## Game 5 — the actual game (`actual`, `ActualGameBip*State`)

"This is the real garden now. No more training!" → keep an eye on the diversity score → "I'm right
here if you need me." Each round Bip reacts to what changed (improvised from the live game state).
It never ends by itself; the restart item goes back to the menu.

## How the stages connect (this branch, `Son`, is Base 2)

| | |
|---|---|
| After `/game new 3` | consent → Introduction → Tutorial |
| Between stages | a **nether portal** opens in front of the player; walking in starts the next stage in a fresh garden (Bip keeps his memory) |
| Tutorial order | 1 Observe → 2 Filter bubble → 3 Recommend → 4 Break the bubble |
| After the tutorial | menu: replay stage 1–4 or **Play the real game** (game 5) |
| Researcher shortcut | `/game stage 1-5` jumps to a stage, `/game stage 0` replays the intro |

Needs the `Son` branch of BeeCuriousService (`bip@4.0`). An earlier "Base 1" version, where the
player picked stages from a menu and moved on with the restart item, is no longer on this branch.
