package io.github.crash2recomp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;

/**
 * On-screen PSX controller.
 *
 * Buttons feed the SAME pad word the physical-pad bypass uses: the activity ORs
 * bits() into mHeldBits/mStickBits and pushes one active-low word through
 * nativeSetGamepad. The left analog stick feeds axes() -> nativeSetSticks,
 * because this game runs as a pinned-analog DualShock (game.toml lock_mode)
 * and movement follows the left stick axes, not the digital D-pad bits.
 * The stick also raises the matching D-pad bits past a threshold so menu
 * screens that only read digital directions keep working.
 *
 * The layout is the PSX one and is deliberately NOT subject to the launcher's
 * "Swap A/B & X/Y" setting. That toggle exists because the Thor's physical
 * buttons sit in Nintendo positions and cannot match the PSX labels; an
 * on-screen button DRAWS the glyph it sends, so swapping it would make the
 * label lie.
 *
 * Every control has a user config slot (offset, scale, hidden) persisted by
 * the launcher as one string (see applyLayout). Offsets are stored as
 * fractions of min(w,h) so a layout edited on one panel survives another.
 *
 * Drawn with Canvas primitives rather than bitmaps, so there are no assets to
 * ship or scale and the glyphs stay sharp at any panel density.
 *
 * Touch handling claims the whole gesture while visible; Android delivers
 * every pointer of a gesture to whichever view accepted the first DOWN, so
 * declining a touch outside the controls would silently break "hold a
 * direction, then press a face button".
 */
public final class TouchPadOverlay extends View {

    /** Visibility policy chosen in the launcher. */
    public enum Mode { OFF, ON, AUTO }

    // PSX pad bit indices -- identical to Crash2SDLActivity's constants.
    private static final int B_SELECT=0, B_START=3, B_UP=4, B_RIGHT=5, B_DOWN=6,
            B_LEFT=7, B_L2=8, B_R2=9, B_L1=10, B_R1=11,
            B_TRI=12, B_CIRCLE=13, B_CROSS=14, B_SQUARE=15;

    /** Classic PSX face colours, so each glyph reads at a glance. */
    private static final int C_TRI    = 0xFF4FD16B;   // green
    private static final int C_CIRCLE = 0xFFE0554E;   // red
    private static final int C_CROSS  = 0xFF5B8DEF;   // blue
    private static final int C_SQUARE = 0xFFE070C0;   // pink
    private static final int C_PLAIN  = 0xFFDDDDDD;   // shoulders / start / select
    private static final int FILL_A   = 0x40;         // button fill alpha
    private static final int EDGE_A   = 0xC0;         // glyph + outline alpha

    /** All movable controls; "stick" is the analog stick, the rest are buttons. */
    public static final String[] IDS = {
        "stick", "tri", "circle", "cross", "square",
        "L1", "L2", "R1", "R2", "SELECT", "START",
    };

    /** Per-control user adjustments, applied on top of the built-in layout. */
    private static final class Cfg {
        float dx, dy;        // offset, as a fraction of min(w,h)
        float scale = 1f;    // size multiplier
        boolean hidden;
    }

    private static final class Btn {
        final int bit; final int colour; final String kind;
        float cx, cy, r;
        /* Touch-target inflation over the drawn circle. START/SELECT sit near
         * the play area's centre line, and a sliding thumb grazing them opens
         * the pause menu mid-jump ("the game presses START by itself") -- they
         * get a TIGHT target while action buttons stay generous. */
        float hitScale = 1.35f;
        boolean hidden;
        Btn(int bit, int colour, String kind) { this.bit=bit; this.colour=colour; this.kind=kind; }
        boolean hit(float x, float y) {
            if (hidden) return false;
            final float dx = x - cx, dy = y - cy;
            final float rr = r * hitScale;
            return dx*dx + dy*dy <= rr*rr;
        }
    }

    private final Btn[] mButtons = {
        new Btn(B_TRI,    C_TRI,    "tri"),
        new Btn(B_CIRCLE, C_CIRCLE, "circle"),
        new Btn(B_CROSS,  C_CROSS,  "cross"),
        new Btn(B_SQUARE, C_SQUARE, "square"),
        new Btn(B_L1,     C_PLAIN,  "L1"),
        new Btn(B_L2,     C_PLAIN,  "L2"),
        new Btn(B_R1,     C_PLAIN,  "R1"),
        new Btn(B_R2,     C_PLAIN,  "R2"),
        new Btn(B_SELECT, C_PLAIN,  "SELECT"),
        new Btn(B_START,  C_PLAIN,  "START"),
    };

    private final java.util.HashMap<String, Cfg> mCfg = new java.util.HashMap<>();

    // Analog stick state. The stick owns one pointer for its whole gesture and
    // keeps tracking it outside the base circle, like a real thumbstick.
    private float mStickCx, mStickCy, mStickR;    // laid-out base position
    private float mBaseCx, mBaseCy;               // active base (moves in dynamic mode)
    private boolean mStickHidden;
    private boolean mDynamic;                     // base re-centres where the thumb lands
    private int mStickPointer = -1;               // pointer id owning the stick
    private float mStickVx, mStickVy;             // current deflection, -1..1 each

    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path  mPath = new Path();

    private volatile int mBits = 0;
    private volatile int mAxes = 0x8080;          // lx | ly<<8, 0x80 = centred
    private Runnable mOnChange;
    private Mode mMode = Mode.OFF;
    /** User size multiplier from the launcher (Small/Medium/Large). */
    private float mScale = 1.0f;

    // ---- edit mode (layout editor) ----------------------------------------
    private boolean mEdit;
    private String mSelected;                     // id of the selected control
    private int mDragPointer = -1;
    private float mDragLastX, mDragLastY;
    private Runnable mOnSelect;

    public TouchPadOverlay(Context ctx) {
        super(ctx);
        setFocusable(false);
        setFocusableInTouchMode(false);
        setClickable(false);          // never intercept key events from a real pad
        mEdge.setStyle(Paint.Style.STROKE);
        mText.setTextAlign(Paint.Align.CENTER);
        mText.setFakeBoldText(true);
        for (String id : IDS) mCfg.put(id, new Cfg());
    }

    /** Bits currently held, in the PSX index space (set = pressed). */
    public int bits() { return mBits; }

    /** Left stick axes as lx | ly<<8, DualShock bytes (0..255, 0x80 centred). */
    public int axes() { return mAxes; }

    /** Invoked whenever the held set or axes change, so the activity can push. */
    public void setOnChange(Runnable r) { mOnChange = r; }

    /**
     * AUTO shows the pad only when no physical controller is attached, so a
     * handheld with a built-in pad is never covered by buttons it does not need.
     */
    public void applyMode(Mode m, boolean physicalPadPresent) {
        mMode = m;
        final boolean show = (m == Mode.ON) || (m == Mode.AUTO && !physicalPadPresent);
        if (!show && (mBits != 0 || mAxes != 0x8080)) {   // never latch a held input
            mBits = 0; mAxes = 0x8080; mStickPointer = -1; mStickVx = mStickVy = 0f;
            fire();
        }
        setVisibility(show ? VISIBLE : GONE);
    }

    public Mode mode() { return mMode; }

    /** Size multiplier; re-lays out immediately so the change is visible. */
    public void setScale(float scale) {
        final float v = Math.max(0.5f, Math.min(1.6f, scale));
        if (v == mScale) return;
        mScale = v;
        relayout();
    }

    /** Dynamic left stick: the base re-centres where the thumb first lands. */
    public void setDynamicStick(boolean on) {
        mDynamic = on;
        mStickPointer = -1; mStickVx = mStickVy = 0f;
        invalidate();
    }

    // ---- per-control layout config ----------------------------------------

    /**
     * Layout string: "id,dx,dy,scale,hidden;..." with dx/dy as fractions of
     * min(w,h). Unknown ids are ignored, missing ids keep defaults, so old
     * strings survive new controls.
     */
    public void applyLayout(String layout) {
        for (String id : IDS) { Cfg c = mCfg.get(id); c.dx = 0; c.dy = 0; c.scale = 1f; c.hidden = false; }
        if (layout != null && !layout.isEmpty()) {
            for (String item : layout.split(";")) {
                final String[] f = item.split(",");
                if (f.length < 5) continue;
                final Cfg c = mCfg.get(f[0]);
                if (c == null) continue;
                try {
                    c.dx = Float.parseFloat(f[1]);
                    c.dy = Float.parseFloat(f[2]);
                    c.scale = Math.max(0.4f, Math.min(2.5f, Float.parseFloat(f[3])));
                    c.hidden = "1".equals(f[4]);
                } catch (NumberFormatException ignored) { }
            }
        }
        relayout();
    }

    /** Serialize the current per-control config (the applyLayout format). */
    public String layoutString() {
        final StringBuilder sb = new StringBuilder();
        for (String id : IDS) {
            final Cfg c = mCfg.get(id);
            if (sb.length() > 0) sb.append(';');
            sb.append(id).append(',').append(c.dx).append(',').append(c.dy)
              .append(',').append(c.scale).append(',').append(c.hidden ? 1 : 0);
        }
        return sb.toString();
    }

    private void relayout() {
        if (getWidth() > 0 && getHeight() > 0) layoutControls(getWidth(), getHeight());
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        layoutControls(w, h);
    }

    /** Built-in layout plus the user's per-control offsets/scales. */
    private void layoutControls(int w, int h) {
        // Base radius as a fraction of the SHORT edge, times the user's size
        // choice; per-control cfg.scale multiplies on top of that.
        final float unit = Math.min(w, h) * 0.085f * mScale;   // face-button radius
        final float pad  = unit * 1.55f;              // margin from the screen edges

        // Right cluster: the PSX diamond, pulled toward the centre and lifted a
        // little off the corner so thumbs curl less, with a wider spread so
        // neighbouring glyphs don't get fat-fingered together.
        final float fx = w - pad - unit * 2.6f;
        final float fy = h - pad - unit * 2.9f;
        final float spread = unit * 1.95f;
        set("tri",    fx,          fy - spread, unit);
        set("circle", fx + spread, fy,          unit);
        set("cross",  fx,          fy + spread, unit);
        set("square", fx - spread, fy,          unit);

        // Left analog stick, mirrored placement of the face cluster.
        final Cfg sc = mCfg.get("stick");
        final float m = Math.min(w, h);
        mStickR  = unit * 2.15f * sc.scale;
        mStickCx = pad + mStickR + unit * 0.7f + sc.dx * m;
        mStickCy = h - pad - mStickR - unit * 0.7f + sc.dy * m;
        mStickHidden = sc.hidden;
        mBaseCx = mStickCx; mBaseCy = mStickCy;

        final float sy = pad * 0.85f;
        final float sr = unit * 0.85f;
        set("L2", pad + sr * 0.2f,     sy, sr);
        set("L1", pad + sr * 2.5f,     sy, sr);
        set("R1", w - pad - sr * 2.5f, sy, sr);
        set("R2", w - pad - sr * 0.2f, sy, sr);

        // Off the bottom edge: flush placement put these inside the system
        // home-gesture zone, and pressing them swiped the game to the
        // background (the "stuck / black screen" reports).
        final float mr = unit * 0.72f;
        set("SELECT", w * 0.5f - mr * 2.4f, h - pad * 0.9f, mr);
        set("START",  w * 0.5f + mr * 2.4f, h - pad * 0.9f, mr);
        for (Btn b : mButtons)
            if ("START".equals(b.kind) || "SELECT".equals(b.kind)) b.hitScale = 1.05f;
    }

    private void set(String kind, float cx, float cy, float r) {
        final Cfg c = mCfg.get(kind);
        final float m = Math.min(getWidth() == 0 ? 1 : getWidth(),
                                 getHeight() == 0 ? 1 : getHeight());
        for (Btn b : mButtons) {
            if (b.kind.equals(kind)) {
                b.cx = cx + (c != null ? c.dx * m : 0);
                b.cy = cy + (c != null ? c.dy * m : 0);
                b.r  = r * (c != null ? c.scale : 1f);
                b.hidden = c != null && c.hidden;
                return;
            }
        }
    }

    // ---- edit mode ---------------------------------------------------------

    /** Layout-editor mode: touches select and drag controls instead of playing. */
    public void setEditMode(boolean on) {
        mEdit = on;
        mSelected = null; mDragPointer = -1;
        mBits = 0; mAxes = 0x8080; mStickPointer = -1; mStickVx = mStickVy = 0f;
        invalidate();
    }

    public void setOnSelect(Runnable r) { mOnSelect = r; }
    public String selected() { return mSelected; }

    public void adjustSelectedScale(float delta) {
        if (mSelected == null) return;
        final Cfg c = mCfg.get(mSelected);
        c.scale = Math.max(0.4f, Math.min(2.5f, c.scale + delta));
        relayout();
    }

    public boolean selectedHidden() {
        return mSelected != null && mCfg.get(mSelected).hidden;
    }

    public void toggleSelectedHidden() {
        if (mSelected == null) return;
        final Cfg c = mCfg.get(mSelected);
        c.hidden = !c.hidden;
        relayout();
    }

    public void resetLayout() { applyLayout(null); }

    private String controlAt(float x, float y) {
        final float dx = x - mStickCx, dy = y - mStickCy;
        if (dx*dx + dy*dy <= mStickR * mStickR) return "stick";
        for (Btn b : mButtons) {
            final float bx = x - b.cx, by = y - b.cy;
            final float rr = b.r * 1.35f;
            if (bx*bx + by*by <= rr*rr) return b.kind;
        }
        return null;
    }

    // ---- input ------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (getVisibility() != VISIBLE) return false;
        if (mEdit) return onEditTouch(e);
        final int action = e.getActionMasked();
        final boolean ending = action == MotionEvent.ACTION_UP
                            || action == MotionEvent.ACTION_CANCEL;

        // Stick pointer lifecycle.
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            final int idx = e.getActionIndex();
            if (mStickPointer < 0 && !mStickHidden && wantsStick(e.getX(idx), e.getY(idx))) {
                mStickPointer = e.getPointerId(idx);
                if (mDynamic) {
                    mBaseCx = clamp(e.getX(idx), mStickR, getWidth() - mStickR);
                    mBaseCy = clamp(e.getY(idx), mStickR, getHeight() - mStickR);
                } else { mBaseCx = mStickCx; mBaseCy = mStickCy; }
            }
        }
        if (ending) {
            mStickPointer = -1;
        } else if (action == MotionEvent.ACTION_POINTER_UP
                   && e.getPointerId(e.getActionIndex()) == mStickPointer) {
            mStickPointer = -1;
        }

        // Stick deflection from its tracked pointer.
        float vx = 0f, vy = 0f;
        if (mStickPointer >= 0) {
            final int pi = e.findPointerIndex(mStickPointer);
            if (pi >= 0) {
                vx = (e.getX(pi) - mBaseCx) / (mStickR * 0.85f);
                vy = (e.getY(pi) - mBaseCy) / (mStickR * 0.85f);
                final float len = (float)Math.sqrt(vx*vx + vy*vy);
                if (len > 1f) { vx /= len; vy /= len; }
                if (len < 0.12f) { vx = 0f; vy = 0f; }   // dead zone
            }
        }
        mStickVx = vx; mStickVy = vy;

        // Buttons from every other pointer.
        int bits = 0;
        if (!ending) {
            final int upIndex = (action == MotionEvent.ACTION_POINTER_UP)
                              ? e.getActionIndex() : -1;
            for (int i = 0; i < e.getPointerCount(); i++) {
                if (i == upIndex || e.getPointerId(i) == mStickPointer) continue;
                bits |= bitsForPoint(e.getX(i), e.getY(i));
            }
        }
        // Digital directions from the stick past a threshold, for menus that
        // only read D-pad bits. Movement itself follows the axes.
        final float th = 0.55f;
        if (vx < -th) bits |= 1 << B_LEFT; else if (vx > th) bits |= 1 << B_RIGHT;
        if (vy < -th) bits |= 1 << B_UP;   else if (vy > th) bits |= 1 << B_DOWN;

        final int axes = axisByte(vx) | (axisByte(vy) << 8);
        if (bits != mBits || axes != mAxes) {
            mBits = bits; mAxes = axes; fire(); invalidate();
        } else if (mStickPointer >= 0) {
            invalidate();   // thumb moved within the same byte values
        }
        return true;   // claim the gesture; see the class comment on multitouch
    }

    /** Where a new pointer grabs the stick: on it, or (dynamic) the left side. */
    private boolean wantsStick(float x, float y) {
        if (mDynamic) {
            final String c = controlAt(x, y);
            if (c != null && !"stick".equals(c)) return false;
            return x < getWidth() * 0.45f;
        }
        final float dx = x - mStickCx, dy = y - mStickCy;
        final float rr = mStickR * 1.25f;
        return dx*dx + dy*dy <= rr*rr;
    }

    private static int axisByte(float v) {
        final int b = 0x80 + Math.round(v * 0x7F);
        return Math.max(0, Math.min(0xFF, b));
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private int bitsForPoint(float x, float y) {
        for (Btn btn : mButtons) if (btn.hit(x, y)) return 1 << btn.bit;
        return 0;
    }

    private boolean onEditTouch(MotionEvent e) {
        final int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            final String id = controlAt(e.getX(), e.getY());
            mSelected = id;
            mDragPointer = id != null ? e.getPointerId(0) : -1;
            mDragLastX = e.getX(); mDragLastY = e.getY();
            if (mOnSelect != null) mOnSelect.run();
            invalidate();
        } else if (action == MotionEvent.ACTION_MOVE && mDragPointer >= 0 && mSelected != null) {
            final int pi = e.findPointerIndex(mDragPointer);
            if (pi >= 0) {
                final float m = Math.min(getWidth(), getHeight());
                final Cfg c = mCfg.get(mSelected);
                c.dx += (e.getX(pi) - mDragLastX) / m;
                c.dy += (e.getY(pi) - mDragLastY) / m;
                mDragLastX = e.getX(pi); mDragLastY = e.getY(pi);
                relayout();
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            mDragPointer = -1;
        }
        return true;
    }

    private void fire() { if (mOnChange != null) mOnChange.run(); }

    // ---- drawing ----------------------------------------------------------

    @Override
    protected void onDraw(Canvas c) {
        if (getVisibility() != VISIBLE) return;
        drawStick(c);
        for (Btn b : mButtons) {
            if (b.hidden && !mEdit) continue;
            drawButton(c, b);
        }
        if (mEdit) drawSelection(c);
    }

    private void drawButton(Canvas c, Btn b) {
        final boolean down = (mBits & (1 << b.bit)) != 0;
        final int dim = b.hidden ? 2 : 1;             // edit mode ghosts hidden controls
        mFill.setColor(withAlpha(b.colour, (down ? FILL_A * 2 : FILL_A) / dim));
        c.drawCircle(b.cx, b.cy, b.r, mFill);
        mEdge.setColor(withAlpha(b.colour, EDGE_A / dim));
        mEdge.setStrokeWidth(Math.max(2f, b.r * 0.09f));
        c.drawCircle(b.cx, b.cy, b.r, mEdge);
        final float g = b.r * 0.46f;                 // glyph half-extent
        switch (b.kind) {
            case "tri":    glyphTriangle(c, b, g); break;
            case "circle": c.drawCircle(b.cx, b.cy, g, mEdge); break;
            case "cross":  glyphCross(c, b, g); break;
            case "square": c.drawRect(b.cx-g, b.cy-g, b.cx+g, b.cy+g, mEdge); break;
            default:       glyphLabel(c, b); break;  // L1/L2/R1/R2/START/SELECT
        }
    }

    private void glyphTriangle(Canvas c, Btn b, float g) {
        mPath.reset();
        mPath.moveTo(b.cx, b.cy - g);
        mPath.lineTo(b.cx + g, b.cy + g * 0.82f);
        mPath.lineTo(b.cx - g, b.cy + g * 0.82f);
        mPath.close();
        c.drawPath(mPath, mEdge);
    }

    private void glyphCross(Canvas c, Btn b, float g) {
        c.drawLine(b.cx - g, b.cy - g, b.cx + g, b.cy + g, mEdge);
        c.drawLine(b.cx + g, b.cy - g, b.cx - g, b.cy + g, mEdge);
    }

    private void glyphLabel(Canvas c, Btn b) {
        mText.setColor(withAlpha(b.colour, EDGE_A / (b.hidden ? 2 : 1)));
        mText.setTextSize(b.r * (b.kind.length() > 2 ? 0.52f : 0.78f));
        final Paint.FontMetrics fm = mText.getFontMetrics();
        c.drawText(b.kind, b.cx, b.cy - (fm.ascent + fm.descent) * 0.5f, mText);
    }

    private void drawStick(Canvas c) {
        if (mStickHidden && !mEdit) return;
        final int dim = mStickHidden ? 2 : 1;
        final boolean active = mStickPointer >= 0;
        // In dynamic mode the base only shows while held (plus a faint resting
        // hint so the zone is discoverable); static mode always shows it.
        final float bx = active ? mBaseCx : mStickCx;
        final float by = active ? mBaseCy : mStickCy;
        final int baseA = (mDynamic && !active && !mEdit) ? FILL_A / 3 : FILL_A;
        mFill.setColor(withAlpha(C_PLAIN, baseA / dim));
        c.drawCircle(bx, by, mStickR, mFill);
        mEdge.setColor(withAlpha(C_PLAIN, (mDynamic && !active && !mEdit ? EDGE_A / 3 : EDGE_A) / dim));
        mEdge.setStrokeWidth(Math.max(2f, mStickR * 0.05f));
        c.drawCircle(bx, by, mStickR, mEdge);
        // Thumb.
        final float tr = mStickR * 0.42f;
        final float tx = bx + mStickVx * mStickR * 0.85f;
        final float ty = by + mStickVy * mStickR * 0.85f;
        mFill.setColor(withAlpha(C_PLAIN, (active ? FILL_A * 3 : FILL_A * 2) / dim));
        c.drawCircle(tx, ty, tr, mFill);
        mEdge.setStrokeWidth(Math.max(2f, tr * 0.09f));
        c.drawCircle(tx, ty, tr, mEdge);
    }

    private void drawSelection(Canvas c) {
        if (mSelected == null) return;
        float cx, cy, r;
        if ("stick".equals(mSelected)) { cx = mStickCx; cy = mStickCy; r = mStickR; }
        else {
            Btn sel = null;
            for (Btn b : mButtons) if (b.kind.equals(mSelected)) sel = b;
            if (sel == null) return;
            cx = sel.cx; cy = sel.cy; r = sel.r;
        }
        mEdge.setColor(0xFFFFC23B);                   // launcher gold
        mEdge.setStrokeWidth(Math.max(3f, r * 0.06f));
        c.drawCircle(cx, cy, r * 1.18f, mEdge);
    }

    private static int withAlpha(int colour, int alpha) {
        return (colour & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }
}
