# Screenshot guidance and GUI operation

This skill supplies no screenshot or input tool. Use the desktop tools your client provides or an image the user gives you. If neither exists, say what you cannot see or do and ask only for the missing image or context.

## Make the next step visible

1. **Identify the real surface.** Name the Bitwig window, project and any open dialog. An isolated display grants no access to the user's main compositor, so capture only the relevant application.
2. **Look at the image itself.** A saved filename or exit code is not inspection. Keep an unmodified original; a crop or annotation must come from it, never from a mockup presented as the running app.
3. **Name the exact context.** The selected track, device breadcrumb, clip and editor; launcher versus arranger ownership; modal dialogs; browser focus; editor tool; automation state. A familiar-looking device is not enough to identify its owner.
4. **Give one action** by a real label and landmark ("in the selected Poly Grid's inspector, the VOICES value field"), with the value only if you know it. For precision prefer numeric entry over dragging a knob.
5. **Say what should change and when to stop.** A compact form: Seen, Next, Expect, Stop if. Attach the real screenshot with your client's image mechanism; a local path is not a displayed image.

In delegated mode do the authorized action, take a new image and check the control before the next host operation.

## Coordinates, focus, freshness

- Find out whether the input tool wants screen, window-relative, client-area or cropped-image coordinates; they do not share raw numbers. Re-measure after a move, resize, scale change or popup, and prefer semantic targeting when the tool really supports it.
- A shortcut is context-sensitive and a menu, search box or plugin window can swallow it. Delivery of the key proves nothing; check the result. Under an isolated X11 session with no window manager, focus the window explicitly because a click may not give keyboard focus.
- Look after each meaningful action. Do not chain guessed clicks across dialogs or make another host call before seeing a pending GUI result. On an unexpected target or stale screenshot, stop and reassess instead of repeating the keys.
- Do not change global shortcuts, GUI scale, layout or input preferences to make a script work.

## Handoff between API and GUI

The API target and the visible editor are separate observations. `notes get` can select a clip while the visible device panel still shows another track; `tracks select` navigates to a track and `devices select` alone does not prove the panel switched. Before a GUI parameter edit verify the header and the control, and after GUI changes re-read the affected refs.

An expanded Grid canvas can still belong to device A after the lower panel shows B: read the canvas title, and if it names the old device, close it and open the new device's Show Expanded Device View control. In the tested 6.1.4 UI the miniature Grid preview was draggable and double-clicking it did not open the editor.

For a known unsupported API feature use an authorized GUI path or manual instructions. For an uncertain API write, stop: the GUI is not a replay channel, and a later correction does not pass an earlier failed criterion.

## Creative changes

Use the [controlled A/B](sound-design.md#controlled-ab). Show comparable before and after framing with the target and changed value visible, labeled as real screenshots. Two screenshots are not an auditory A/B. Report separately what the image shows, what a read verified, whether anything was rendered, whether anyone listened (otherwise "No audio audition performed") and whether save and reopen were checked. The user decides which candidate stays.
