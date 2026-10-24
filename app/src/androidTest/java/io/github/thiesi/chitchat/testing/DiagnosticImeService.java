package io.github.thiesi.chitchat.testing;

import android.graphics.Color;
import android.inputmethodservice.InputMethodService;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import io.github.thiesi.chitchat.test.R;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

enum DiagnosticImeEvent {
    CREATE, INPUT_START, INPUT_VIEW_START, INPUT_VIEW_FINISH, WINDOW_SHOWN, WINDOW_HIDDEN,
    ROOT_ATTACHED, ROOT_VISIBLE, ROOT_INVISIBLE, ROOT_GONE, ROOT_DETACHED, DESTROY, LIMIT
}

/** Framework-only ABI: the standalone test-package process has no target-app dependencies. */
final class DiagnosticImeEvents {
    private final int limit;
    private final LongSupplier uptime;
    private final Consumer<String> emit;
    private int count;

    DiagnosticImeEvents() { this(1024); }
    DiagnosticImeEvents(int limit) {
        this(limit, SystemClock::uptimeMillis, line -> Log.i("ChitChatTestIme", line));
    }
    DiagnosticImeEvents(int limit, LongSupplier uptime, Consumer<String> emit) {
        if (limit < 1 || limit > 1024) throw new IllegalArgumentException("Invalid diagnostic event bound");
        this.limit = limit;
        this.uptime = uptime;
        this.emit = emit;
    }
    void record(DiagnosticImeEvent event) {
        if (count > limit) return;
        count++;
        DiagnosticImeEvent kind = count > limit ? DiagnosticImeEvent.LIMIT : event;
        emit.accept("n=" + count + " t=" + uptime.getAsLong() + " e=" + kind.name());
    }
}

/**
 * Minimal test-APK-only framework control. Java deliberately avoids Kotlin/AndroidX dependencies
 * stripped from the test APK and supplied only inside instrumentation by the target app.
 * No editor reads, network, stored EditorInfo/connections, timers or focus/visibility repairs.
 */
public final class DiagnosticImeService extends InputMethodService {
    private final DiagnosticImeEvents events = new DiagnosticImeEvents();

    @Override public void onCreate() { super.onCreate(); events.record(DiagnosticImeEvent.CREATE); }
    @Override public void onStartInput(EditorInfo info, boolean restarting) {
        super.onStartInput(info, restarting);
        events.record(DiagnosticImeEvent.INPUT_START);
    }
    @Override public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        events.record(DiagnosticImeEvent.INPUT_VIEW_START);
    }
    @Override public void onFinishInputView(boolean finishingInput) {
        events.record(DiagnosticImeEvent.INPUT_VIEW_FINISH);
        super.onFinishInputView(finishingInput);
    }
    @Override public void onWindowShown() {
        super.onWindowShown();
        events.record(DiagnosticImeEvent.WINDOW_SHOWN);
    }
    @Override public void onWindowHidden() {
        events.record(DiagnosticImeEvent.WINDOW_HIDDEN);
        super.onWindowHidden();
    }
    @Override public View onCreateInputView() {
        LinearLayout panel = new LinearLayout(this) {
            @Override protected void onAttachedToWindow() {
                super.onAttachedToWindow();
                events.record(DiagnosticImeEvent.ROOT_ATTACHED);
            }
            @Override protected void onDetachedFromWindow() {
                events.record(DiagnosticImeEvent.ROOT_DETACHED);
                super.onDetachedFromWindow();
            }
            @Override protected void onWindowVisibilityChanged(int visibility) {
                super.onWindowVisibilityChanged(visibility);
                if (visibility == View.VISIBLE) events.record(DiagnosticImeEvent.ROOT_VISIBLE);
                else if (visibility == View.INVISIBLE) events.record(DiagnosticImeEvent.ROOT_INVISIBLE);
                else if (visibility == View.GONE) events.record(DiagnosticImeEvent.ROOT_GONE);
            }
        };
        panel.setOrientation(LinearLayout.VERTICAL);
        int height = Math.round(200 * getResources().getDisplayMetrics().density);
        panel.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height));
        panel.setMinimumHeight(height);
        panel.setBackgroundColor(Color.DKGRAY);
        TextView title = new TextView(this);
        title.setText(R.string.diagnostic_ime_name);
        title.setTextColor(Color.WHITE);
        panel.addView(title);
        Button space = new Button(this);
        space.setText(R.string.diagnostic_ime_space);
        space.setOnClickListener(view -> {
            InputConnection connection = getCurrentInputConnection();
            if (connection != null) connection.commitText(" ", 1);
        });
        panel.addView(space);
        Button delete = new Button(this);
        delete.setText(R.string.diagnostic_ime_delete);
        delete.setOnClickListener(view -> {
            InputConnection connection = getCurrentInputConnection();
            if (connection != null) connection.deleteSurroundingText(1, 0);
        });
        panel.addView(delete);
        return panel;
    }
    @Override public void onDestroy() { events.record(DiagnosticImeEvent.DESTROY); super.onDestroy(); }
}
