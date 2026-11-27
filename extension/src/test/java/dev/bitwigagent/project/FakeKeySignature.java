package dev.bitwigagent.project;

import com.google.gson.JsonObject;
import java.util.*;

/** A simulated native key signature: Bitwig's option lists, writes recorded and applied unless told to drop them. */
public final class FakeKeySignature implements KeySignatureModule.Access {
    static final String[] ROOTS = {"C", "C♯/D♭", "D", "D♯/E♭", "E", "F", "F♯/G♭", "G", "G♯/A♭", "A", "A♯/B♭", "B"};
    static final int MAJOR = 2741, MINOR = 1453;

    final List<Map<String, Integer>> writes = new ArrayList<>();
    int root, scale = MAJOR;
    boolean dropWrites;

    @Override
    public void execute(Runnable work) {
        work.run();
    }

    @Override
    public void provenance(JsonObject out) {
        out.addProperty("nativeVersion", "fake");
    }

    @Override
    public KeySignatureModule.State read(Object projectProxy) {
        List<KeySignatureModule.Option> roots = new ArrayList<>();
        for (int i = 0; i < ROOTS.length; i++) roots.add(new KeySignatureModule.Option(i, ROOTS[i]));
        List<KeySignatureModule.Option> scales =
                List.of(new KeySignatureModule.Option(MAJOR, "Major"), new KeySignatureModule.Option(MINOR, "Minor"));
        String name = scale == MAJOR ? "major" : scale == MINOR ? "minor" : "custom [" + scale + "]";
        return new KeySignatureModule.State(
                new KeySignatureModule.Parameter(root, roots), new KeySignatureModule.Parameter(scale, scales), name);
    }

    @Override
    public void write(Object projectProxy, Map<String, Integer> values, Runnable beforeWrite) {
        beforeWrite.run();
        writes.add(new LinkedHashMap<>(values));
        if (dropWrites) return;
        root = values.getOrDefault("root", root);
        scale = values.getOrDefault("scale", scale);
    }
}
