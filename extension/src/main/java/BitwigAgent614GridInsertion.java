import com.bitwig.base.async.oRV;
import com.bitwig.flt.document.core.master.IG1;
import com.bitwig.flt.document.core.master.g2L;
import com.bitwig.flt.document.core.master.IbI;
import dev.bitwigagent.GridInsertionBridge;

/** Loaded only by name after exact binary and live Class-identity checks. */
public final class BitwigAgent614GridInsertion extends bTA implements GridInsertionBridge {
    private final GridInsertionBridge.Callback callback;

    public BitwigAgent614GridInsertion(GridInsertionBridge.Callback callback) {
        super(null); // Native rejection cleanup must precede outward request failure notification.
        this.callback = java.util.Objects.requireNonNull(callback);
    }
    // javac ignores bTA's obfuscated synthetic bridges; retain their exact native conversion targets.
    @Override public Object cfM(Object value, IbI context) { return super.MRY(value, context); }
    @Override public Object Hnt(Object value, IbI context) { return super.FzB(value, context); }
    @Override public void load(Object project, String path) {
        ((g2L) project).cfM(new String[]{path}, this);
    }
    @Override public void cfM(IG1 converted, oRV completion) {
        try {
            if (callback.delivered(converted, completion)) callback.returned();
        } catch (Throwable error) { callback.adapterFailure(error); }
        // Return normally so master.cen can perform its own context pop. Never pop it here.
    }
    @Override public void cfM(String message) {
        try { callback.earlyError(message); }
        catch (Throwable error) { callback.adapterFailure(error); }
    }
}
