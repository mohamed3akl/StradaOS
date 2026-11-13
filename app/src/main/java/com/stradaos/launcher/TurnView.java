package com.stradaos.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Flecha del próximo giro (derecho, izquierda, derecha, vuelta en U, glorieta o llegada). */
public class TurnView extends View {

    private float angle;
    private boolean roundabout, arrive;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    public TurnView(Context c) {
        super(c);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
    }

    void set(Nav.Step s) {
        angle = Nav.angle(s);
        roundabout = Nav.isRoundabout(s);
        arrive = s != null && "arrive".equals(s.type);
        invalidate();
    }

    void setColor(int c) { p.setColor(c); invalidate(); }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), u = Math.min(w, h);
        float sw = u * 0.11f;
        p.setStrokeWidth(sw);
        float cx = w / 2f, cy = h * 0.55f;
        if (arrive) {
            // bandera: asta y banderín
            p.setStyle(Paint.Style.STROKE);
            c.drawLine(cx - u * 0.2f, h * 0.88f, cx - u * 0.2f, h * 0.12f, p);
            p.setStyle(Paint.Style.FILL);
            path.reset();
            path.moveTo(cx - u * 0.2f, h * 0.12f);
            path.lineTo(cx + u * 0.3f, h * 0.25f);
            path.lineTo(cx - u * 0.2f, h * 0.40f);
            path.close();
            c.drawPath(path, p);
            p.setStyle(Paint.Style.STROKE);
            return;
        }
        if (roundabout) {
            float r = u * 0.2f;
            p.setStyle(Paint.Style.STROKE);
            c.drawCircle(cx, cy - u * 0.05f, r, p);
            c.drawLine(cx, h * 0.92f, cx, cy - u * 0.05f + r, p);
            arrowFrom(c, cx, cy - u * 0.05f, r, u * 0.38f, angle == 0 ? 0 : angle);
            return;
        }
        // tronco desde abajo y flecha hacia el ángulo del giro
        float stem = u * 0.30f;
        c.drawLine(cx, h * 0.92f, cx, cy, p);
        if (angle == 180) {
            // vuelta en U: arco hacia la izquierda y baja
            float r = u * 0.18f;
            RectF o = new RectF(cx - 2 * r, cy - r, cx, cy + r);
            path.reset();
            path.addArc(o, 0, -180);
            c.drawPath(path, p);
            float ex = cx - 2 * r, ey = cy + u * 0.18f;
            c.drawLine(ex, cy, ex, ey, p);
            head(c, ex, ey, 180, u);
            return;
        }
        arrowFrom(c, cx, cy, 0, stem, angle);
    }

    private void arrowFrom(Canvas c, float cx, float cy, float r0, float len, float ang) {
        double a = Math.toRadians(ang);
        float sx = cx + (float) Math.sin(a) * r0, sy = cy - (float) Math.cos(a) * r0;
        float ex = cx + (float) Math.sin(a) * (r0 + len), ey = cy - (float) Math.cos(a) * (r0 + len);
        c.drawLine(sx, sy, ex, ey, p);
        head(c, ex, ey, ang, Math.min(getWidth(), getHeight()));
    }

    /** Punta de flecha en (x, y) apuntando al ángulo dado (0 = arriba). */
    private void head(Canvas c, float x, float y, float ang, float u) {
        float l = u * 0.17f;
        double a = Math.toRadians(ang);
        for (int sgn = -1; sgn <= 1; sgn += 2) {
            double b = a + Math.PI + sgn * Math.toRadians(38);
            c.drawLine(x, y, x + (float) Math.sin(b) * l, y - (float) Math.cos(b) * l, p);
        }
    }
}
