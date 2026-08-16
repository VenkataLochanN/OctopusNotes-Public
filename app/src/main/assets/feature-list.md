# Feature List

> Both editors — the paged notebook and the infinite whiteboard — share the same tool dock.
> The paged notebook has the full tool set; the whiteboard has the core six tools (noted below).

## Tools

### Pen

**Tool settings**

- Stroke options: Solid | Dotted | Dashed
- Stroke stabilisation: 1–10 (Default 5)
- Smart Shape (always on): hold a pen scribble still and it snaps to a perfect shape —
  Line | Arrow | Rectangle / Square | Circle / Ellipse | Triangle
- Scribble to erase (always on): scribble back and forth over ink with the pen to erase it
  (difficulty: Easy | Hard, set in Settings → Editing)
- 5 colour options (changeable — tap to use, tap again to edit)
- 3 size options (changeable — tap to use, tap again to fine-tune)

**Ink behaviour**

- Hold-to-position lines: draw a line, hold still for about half a second, then swing the
  far end to aim the angle and stretch the length while the first point stays pinned
- Live measurement pill while drawing (length + angle for lines, W × H for shapes)
- Every stroke is undoable

### Highlighter

**Tool settings**

- Shape options: Free highlighter | Straight highlighter
- Mode options: Normal highlighter | Text highlighter (snaps to PDF text — notebook only)
- 5 colour options (changeable: yellow, green, pink, blue, orange by default)
- 3 size options (changeable)
- Highlight strokes render underneath pen ink
- Straight highlighters support hold-to-position like pen lines

### Eraser

**Tool options**

- Pixel eraser: rubs out exactly what you move over
- Stroke eraser: removes whole strokes at once
- 3 size options (changeable)
- Long-press the pen still → temporary eraser until you lift (stylus; enable in Settings)

### Selection (Lasso)

**Tool options**

- Free selection
- Rectangular selection
- Circular selection

**Selected-object actions**

- Move, resize (corner + side handles), rotate (angle slider), duplicate, delete
- More options: flip horizontally, flip vertically, copy, cut, paste
- Quick colour row: recolour with the 5 pen colours, or open the full colour picker
- Live measurement readout: W × H in real mm/cm, plus true length and angle for straight lines
- Ghost movement box for tiny selections (when handles would overlap)
- Selection box stays put on screen when you scroll or pan

### Image tool (notebook only)

**Tool options**

- Choose a photo from the gallery / recent photos
- Capture with the camera
- Every insert goes through the full-screen crop screen (crop + rotate) before placing
- Placed images are selectable: move, resize, rotate, duplicate, delete, flip, copy, cut, paste

### Text tool (notebook only)

**Tool options**

- Tap anywhere on the page and type
- Size presets: Small | Medium | Large
- Uses the current pen colour
- Move or resize the text afterwards

### Shape tool

**Tool options**

- Shapes: Rectangle | Oval | Triangle | Line | Arrow
- Colour and thickness follow the pen's settings
- Smart Shape (always on): a pen scribble held still snaps to the nearest shape
- Live W × H (and length + angle for lines and arrows) while drawing
- Lines and arrows support hold-to-position; complete shapes glide as one piece

### Table tool (notebook only)

**Tool settings**

- Drag on the page to draw the table area (live preview)
- Rows / Columns steppers (3 × 3 default)
- Header row toggle: Header ON / OFF
- First column toggle: 1st col ON / OFF
- Header row and first-column shading colours
- Corner radius (rounded corners)
- Border style: Solid | Dotted | Dashed; 3 thickness presets; 5 pen colour slots

**After insert — cell editor and table options**

- Tap a cell to type its text
- Table options popup (live grid preview):
  - Resize individual rows and columns (−/+ px steppers)
  - Add row above / below, add column left / right
  - Delete row / column
  - Merge right / merge down; split cell
- Row and column sizes are stored as proportions, so they survive whole-table resizing
- Merged cells render as one clean cell (grid lines clipped, text pulled together)

### Laser tool

**Tool options**

- 5 colour options (changeable)
- 3 size options (changeable; notebook follows the pen's sizes until customised)
- Draws like a real laser: bright tip with a trail that fades behind it
- Trail is ephemeral — nothing is drawn, saved or undoable

### Measure tool (notebook only)

- Tap two points, or tap and drag from the first
- A line with white endpoint markers, plus a pill at the midpoint showing the
  distance and angle (e.g. 12.5 cm · 32°)
- Pure on-screen ruler: nothing is drawn, saved or undoable
- Pinned to the page — scrolling and zooming keep the ruler where you placed it
- Distance is the true physical length at any zoom

### Whiteboard (infinite canvas)

- Same shared dock: Pen, Highlighter, Eraser, Selection, Shape, Laser
  (no image, text, table or measure tool)
- One unbounded canvas: pan and zoom anywhere, no pages
- Single-finger action: Scroll | Draw | Ignored (two fingers always zoom and pan)
- Zoom indicator + fit-to-content
- Strokes are saved automatically
- Its own tool settings persist separately from the notebook

## Gestures & Stylus

- Single finger: Scroll | Draw | Ignored
- Two fingers: always zoom and pan
- Stylus pen type: S Pen | Other (Xiaomi, Huawei, etc.)
- Primary button action: Switch to last used | Eraser | Highlighter | Pen | Laser | Lasso | Disabled
- Secondary button action (non-S Pen styluses): same options
- Long-hold primary button → eraser (on/off)
- Long-press pen still → temporary eraser (Settings → Editing)

## Settings (Editing)

- **Add pages continuously**: writing on the last page appends a new page automatically
  (no need to tap +)
- **Canvas color**: the colour behind the pages — matches your wallpaper by default
  (Material You), or pick any colour
- **Scribble to erase** difficulty: Easy (a few passes) | Hard (denser scribble required)
- **Long-press pen to erase**: hold the pen still on ink to erase until you lift
- **Show tool options**: options appear automatically when you switch tools (on/off)

## Misc

- Undo & redo every change
- Active colour swatch ring follows the Material You theme
- Size buttons show their real size (dynamic icons that scale to the actual value)
- Highlighter swatches keep their colour in light and dark mode (white backing)
- Tool settings (sizes, colours, styles) are remembered per editor and restored on restore
