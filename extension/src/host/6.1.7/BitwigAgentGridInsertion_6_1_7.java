import com.bitwig.base.async.PPR;
import com.bitwig.flt.document.core.master.Awj;
import com.bitwig.flt.document.core.master.CmH;
import com.bitwig.flt.document.core.master.Mv;
import dev.bitwigagent.grid.GridInsertionBridge;

/** Loaded only by name after exact binary and live Class-identity checks. */
public final class BitwigAgentGridInsertion_6_1_7 extends Zsf implements GridInsertionBridge {
    private final GridInsertionBridge.Callback callback;

    public BitwigAgentGridInsertion_6_1_7(GridInsertionBridge.Callback callback) {
        super(null); // Native rejection cleanup must precede outward request failure notification.
        this.callback = java.util.Objects.requireNonNull(callback);
    }
    // javac ignores Zsf's obfuscated synthetic bridges; retain their exact native conversion targets.
    @Override
    public Object beI(Object value, Awj context) {
        return super.Gef(value, context);
    }

    @Override
    public Object sCA(Object value, Awj context) {
        return super.MgQ(value, context);
    }

    @Override
    public void load(Object project, String path) {
        ((CmH) project).beI(new String[] {path}, this);
    }

    @Override
    public void beI(Mv converted, PPR completion) {
        try {
            if (callback.delivered(converted, completion)) callback.returned();
        } catch (Throwable error) {
            callback.adapterFailure(error);
        }
        // Return normally so master.cen can perform its own context pop. Never pop it here.
    }

    @Override
    public void beI(String message) {
        try {
            callback.earlyError(message);
        } catch (Throwable error) {
            callback.adapterFailure(error);
        }
    }
}
