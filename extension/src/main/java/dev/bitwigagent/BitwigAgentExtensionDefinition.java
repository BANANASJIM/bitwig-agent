package dev.bitwigagent;

import com.bitwig.extension.api.PlatformType;
import com.bitwig.extension.controller.AutoDetectionMidiPortNamesList;
import com.bitwig.extension.controller.ControllerExtension;
import com.bitwig.extension.controller.ControllerExtensionDefinition;
import com.bitwig.extension.controller.api.ControllerHost;
import java.util.UUID;

public final class BitwigAgentExtensionDefinition extends ControllerExtensionDefinition {
    public String getName() { return "Bitwig Agent"; }
    public String getAuthor() { return "Bitwig Agent contributors"; }
    public String getVersion() { return "0.2.0"; }
    public UUID getId() { return UUID.fromString("e619a1d7-fc56-4225-8e2f-7cc293b11e54"); }
    public int getRequiredAPIVersion() { return 25; }
    public String getHardwareVendor() { return "Bitwig Agent"; }
    public String getHardwareModel() { return "Local API bridge"; }
    public int getNumMidiInPorts() { return 0; }
    public int getNumMidiOutPorts() { return 0; }
    public void listAutoDetectionMidiPortNames(AutoDetectionMidiPortNamesList list, PlatformType platform) { }
    public ControllerExtension createInstance(ControllerHost host) {
        return new BitwigAgentExtension(this, host);
    }
}
