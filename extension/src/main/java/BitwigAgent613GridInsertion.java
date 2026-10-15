import com.bitwig.base.async.jJa;
import com.bitwig.flt.document.core.master.CPg;
import com.bitwig.flt.document.core.master.WN2;
import com.bitwig.flt.document.core.master.UhY;
import dev.bitwigagent.GridInsertionBridge;

/** Loaded only by name after exact binary and live Class-identity checks. */
public final class BitwigAgent613GridInsertion extends jDf implements GridInsertionBridge {
    private final GridInsertionBridge.Callback callback;

    public BitwigAgent613GridInsertion(GridInsertionBridge.Callback callback) {
        super(null); // Native rejection cleanup must precede outward request failure notification.
        this.callback = java.util.Objects.requireNonNull(callback);
    }
    // javac ignores jDf's obfuscated synthetic bridges; retain their exact native conversion targets.
    @Override public Object vhT(Object value, UhY context) { return super.E3I(value, context); }
    @Override public Object HEY(Object value, UhY context) { return super.kpO(value, context); }
    @Override public void load(Object project, String path) {
        ((WN2) project).vhT(new String[]{path}, this);
    }
    @Override public void vhT(CPg converted, jJa completion) {
        try {
            if (callback.delivered(converted, completion)) callback.returned();
        } catch (Throwable error) { callback.adapterFailure(error); }
        // Return normally so dl1 can perform its own context pop. Never pop it here.
    }
    @Override public void vhT(String message) {
        try { callback.earlyError(message); }
        catch (Throwable error) { callback.adapterFailure(error); }
    }
}
