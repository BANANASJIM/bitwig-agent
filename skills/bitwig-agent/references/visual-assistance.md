# Screenshot guidance and GUI operation

The skill is instructions, not a screenshot/input implementation. Bitwig Agent's structured tools do not provide universal screen capture or control of every UI element. Use actual desktop tools supplied by the client, or a screenshot supplied by the user. If those are unavailable, say what cannot be observed or operated and request only the missing image/context needed for the next step.

## Make the next step visible

1. **Identify the real surface.** Establish the intended Bitwig process/session/window, project provenance and active dialog. Respect the user's desktop boundary; an isolated display does not grant access to the main compositor. Capture only the relevant application surface.
2. **Inspect the image itself.** A saved filename, tool exit code or model readback is not visual inspection. Read the actual image. Preserve an unmodified original; a crop or annotation must come from that image, not a generated mockup presented as the running app.
3. **Locate the exact context.** Name the selected track, device/breadcrumb, clip and editor shown. Check Launcher versus Arranger ownership, modal dialogs, browser/search focus, editor tool, relevant automation state and any selection-triggered arming. A familiar device appearance is not enough to identify its owner.
4. **Explain one useful action.** Refer to an actual label and stable landmark: for example, “In the selected Poly Grid's inspector, use the visible VOICES value field.” State the intended value/units only when known. Prefer an observed numeric-entry interaction over a blind knob drag when precision matters.
5. **State the expected observation.** Tell the user what should visibly change and when to stop. In delegated mode, perform the authorized action, capture a new image and inspect the relevant control/result before another host operation in that sequence.

A concise response can be: **Seen:** the exact current context. **Next:** the control and action. **Expect:** the visible state/value. **Stop if:** the actual dialog or target differs. Attach the real screenshot using the conversation's supported image mechanism; do not claim to have displayed an image if only a local path was produced.

## Coordinates, focus and freshness

- Establish whether the input tool expects **screen**, **window-relative**, **client-area** or **cropped-image** coordinates. A root screenshot and a window-relative click cannot share raw coordinates. Account for window origin, decorations, display scaling and any image resize/crop; do not infer these from memory.
- Reobserve geometry after a move, resize, scale change or new popup. Prefer semantic/accessibility targeting when the available tool actually supports it; do not invent selectors.
- A keyboard shortcut is context-sensitive. A menu, browser search, value editor or plugin window may consume it. Verify the active window and relevant control; successful key delivery does not prove the command executed. In an isolated X11 session without a window manager, explicitly focus the identified application window: a pointer click may not establish keyboard focus.
- Inspect after each meaningful action. Do not chain guessed clicks across dialogs or issue another host call before viewing a pending GUI postcondition. An unexpected target/value, stale screenshot or failed image inspection stops the current sequence; retain the observed state and reassess rather than pressing the same keys repeatedly.
- Do not silently change global shortcuts, GUI scale, window layout or input preferences to make an automation script work.

## Handoff between API and GUI

An API target and the visible editor are separate observations. In particular, `notes.read` can select a clip while the visible device panel still belongs to another track. `tracks.select` requests owning-track navigation; `devices.select` alone is not proof that the visible panel switched tracks. Treat a dispatch-only selection receipt as a reason to inspect, not an acknowledgement; an actual unknown/error still stops writes. Before a GUI parameter edit, explicitly verify the track/device header and actual control. After GUI changes, reacquire affected API targets and tokens rather than reusing stale bindings.

An expanded Grid canvas can still belong to A after the lower device panel switches to B. Check the canvas's own track/device title. If it still names the previous device, close that editor and open the intended device's **Show Expanded Device View** control on its left strip; inspect the new canvas title before touching a parameter. In the tested 6.1.4 UI, the miniature Grid preview was draggable and double-clicking it did not establish an expanded editor. Confirm the actual control's tooltip/status instead of assuming a preview click opens it.

For a **known unsupported API feature**, use an authorized GUI path or give manual instructions. For an **uncertain API write**, stop; GUI is not a fallback or replay channel. Observe the [unknown-outcome rules](targeting-and-evidence.md) and keep the original failed criterion failed even if a separately planned later correction succeeds.

Grid work should combine the actual canvas/inspector image with [sound-design reasoning](sound-design.md) and [binding limits](grid-and-recipes.md). A picture of cables or changed knobs establishes neither the active engine signal graph nor sound quality.

## Creative changes and evidence

Use the [controlled A/B procedure](sound-design.md#controlled-ab). Preserve A before touching B. Show comparable before/after framing with the exact selected target and changed value visible; label genuine screenshots rather than redrawing the UI. If the musical question requires audio, obtain authorized playback or render evidence with comparable conditions. Do not call two screenshots an auditory A/B test.

Report separately: what the image shows, what an API/model read verifies, whether the output was rendered, whether anyone actually auditioned it, and whether save/reopen was checked. If no audio was heard, say **No audio audition performed**. Let the user decide which creative candidate to keep.
