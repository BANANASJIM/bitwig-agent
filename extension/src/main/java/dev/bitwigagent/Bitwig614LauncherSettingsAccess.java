package dev.bitwigagent;

import com.google.gson.*;
import java.lang.reflect.Method;
import java.util.*;
import static dev.bitwigagent.HostServices.failure;

/** Exact 6.1.4 bindings for a source-bound launcher slot; no cursor or GUI selection. */
final class Bitwig614LauncherSettingsAccess implements LauncherSettingsModule.Access {
    private final Bitwig614Runtime.Access a;
    private final NativeReadContext context;
    private final Class<?> event, slotType, projectType;
    private final Method target, clip, slot, owner, uuid, project, name, time, playStart, playStop, loop, loopEnabled;
    private final Method position, duration, doubleGet, boolGet, actionString;
    private final Map<String, Method> getters = new LinkedHashMap<>(), setters = new LinkedHashMap<>();
    private final Map<String, Object> actions = new LinkedHashMap<>();
    Bitwig614LauncherSettingsAccess(Bitwig614Runtime.Access a) throws ReflectiveOperationException {
        this.a = a;
        context = new NativeReadContext(new Bitwig614ReadContextAccess(a));
        event = a.type("zZi"); slotType = a.type("Us"); projectType = a.type("com.bitwig.flt.document.core.master.g2L");
        Class<?> obj=a.type("com.bitwig.ramona.core.AVL"), proxy=a.type("com.bitwig.flt.control_surface.proxy.ControlSurfaceProxy");
        target=a.exactMethod(proxy,"getTarget",Object.class,false);
        clip=a.exactMethod(a.type("com.bitwig.flt.control_surface.proxy.ClipLauncherSlotProxy"),"getClip",a.type("Ob"),false);
        slot=a.exactMethod(a.type("Ob"),"kFU",slotType,false);
        owner=a.exactMethod(a.type("PAJ"),"Z1C",a.type("com.bitwig.flt.document.core.iface.WSA"),false);
        uuid=a.exactMethod(a.type("com.bitwig.flt.document.core.iface.RYd"),"kFU",UUID.class,false);
        project=a.exactMethod(obj,"IwV",Object.class,false,Class.class);
        name=a.exactMethod(a.type("Ob"),"dJ_",String.class,false);
        Class<?> timing=a.type("mdC"), number=a.type("com.bitwig.ramona.core.iYN"), bool=a.type("com.bitwig.ramona.core.lBQ");
        time=a.exactMethod(a.type("eib"),"Nnn",timing,false);
        playStart=a.exactMethod(a.type("RsJ"),"TMF",a.type("etA"),false);
        playStop=a.exactMethod(a.type("RsJ"),"hy_",a.type("XbH"),false);
        loop=a.exactMethod(a.type("nLa"),"tp2",a.type("PTe"),false);
        loopEnabled=a.exactMethod(a.type("nLa"),"LaE",bool,false);
        position=a.exactMethod(a.type("vFg"),"Ktv",number,false);
        duration=a.exactMethod(a.type("iCu"),"Coi",number,false);
        doubleGet=a.exactMethod(number,"getDoubleValue",double.class,false);
        boolGet=a.exactMethod(bool,"getBoolValue",boolean.class,false);
        Class<?> t=a.type("t3q"), action=a.type("bY");
        bind(t,"nextAction","FPW","cfM",action);
        bind(t,"nextActionEnabled","Yt","rGR",boolean.class);
        bind(t,"nextActionLinkToLoop","Pus","Lcd",boolean.class);
        bind(t,"nextActionLoopCount","PdY","TMF",int.class);
        bind(t,"nextActionDelayBeats","sWR","jFL",double.class);
        actionString=a.exactMethod(action,"MRY",String.class,false);
        for(Object value:action.getEnumConstants()) actions.put((String)a.invoke(actionString,value),value);
        if(!actions.containsKey("play_next")||!actions.containsKey("stop")) throw new NoSuchMethodException("Required follow-action enum values absent");
    }
    private void bind(Class<?> owner,String field,String get,String set,Class<?> type) throws ReflectiveOperationException {
        getters.put(field,a.exactMethod(owner,get,type,false)); setters.put(field,a.exactMethod(owner,set,void.class,false,type));
    }
    @Override public void execute(Runnable work) { a.execute(work); }
    @Override public JsonObject apply(Object proxy,String trackId,String expectedName,JsonObject changes,Runnable check,Runnable beforeWrite) {
        a.documentThread();check.run();
        Object nativeSlot=a.invoke(target,proxy), value=a.invoke(clip,proxy);
        if(!slotType.isInstance(nativeSlot)||!event.isInstance(value)||a.invoke(slot,value)!=nativeSlot)
            throw failure("stale_context","Launcher proxy does not resolve to one attached native clip slot.");
        Object track=a.invoke(owner,nativeSlot);
        if(track==null||!UUID.fromString(trackId).equals(a.invoke(uuid,track))) throw failure("stale_context","Native owning track UUID differs.");
        if(!expectedName.equals(a.invoke(name,value))) throw failure("stale_context","Native clip name differs from expectedClipName.");
        Object owningProject=a.invoke(project,value,projectType);
        Object[] models={value,nativeSlot,track};String[] labels={"clipDeleted","slotDeleted","trackDeleted","projectDeleted"};
        NativeReadContext.Capture capture=context.capture(owningProject,models,labels,check,false);
        a.requireModelThread(value);
        Object timing=a.invoke(time,value), loopRange=a.invoke(loop,timing);
        Map<String,Object> properties=new LinkedHashMap<>();
        properties.put("playStartBeats",a.invoke(position,a.invoke(playStart,timing)));
        properties.put("playStopBeats",a.invoke(position,a.invoke(playStop,timing)));
        properties.put("loopStartBeats",a.invoke(position,loopRange));
        properties.put("loopLengthBeats",a.invoke(duration,loopRange));
        properties.put("loopEnabled",a.invoke(loopEnabled,timing));
        JsonObject before=read(value,properties);
        Runnable guard=()->{check.run();a.requireModelThread(value);context.guard(capture,models,labels,check);
            if(a.invoke(target,proxy)!=nativeSlot||a.invoke(clip,proxy)!=value||a.invoke(slot,value)!=nativeSlot
                    ||!UUID.fromString(trackId).equals(a.invoke(uuid,a.invoke(owner,nativeSlot))))throw failure("stale_context","Launcher target changed during native operation.");};
        guard.run();JsonObject fields=new JsonObject();
        for(String key:LauncherSettingsModule.WRITE_ORDER){
            if(!changes.has(key))continue;
            JsonElement requested=changes.get(key),original=before.get(key);
            if(!LauncherSettingsModule.matches(original,requested)){
                guard.run();beforeWrite.run();
                {Object input;
                    if(key.equals("nextAction"))input=actions.get(requested.getAsString());
                    else if(key.equals("nextActionLoopCount"))input=Integer.valueOf(requested.getAsInt());
                    else if(key.equals("nextActionDelayBeats"))input=Double.valueOf(requested.getAsDouble());
                    else input=Boolean.valueOf(requested.getAsBoolean());
                    if(input==null)throw failure("invalid_params","Unsupported native nextAction");a.invoke(setters.get(key),value,input);}
            }
        }
        guard.run();JsonObject after=read(value,properties);
        for(var e:before.entrySet())if(!changes.has(e.getKey())&&!LauncherSettingsModule.matches(e.getValue(),after.get(e.getKey())))
            throw new RpcException("unexpected_side_effect","Native write changed omitted setting: "+e.getKey(),"unknown");
        for(var e:changes.entrySet()){
            JsonObject f=new JsonObject();f.add("requested",e.getValue());f.add("observed",after.get(e.getKey()));
            boolean match=LauncherSettingsModule.matches(e.getValue(),after.get(e.getKey()));f.addProperty("matchesRequested",match);
            f.addProperty("verification",LauncherSettingsModule.matches(before.get(e.getKey()),e.getValue())?"already_matching_native_read":"native_model_read_after_write");fields.add(e.getKey(),f);
            if(!match)throw new RpcException("write_not_observed","Native clip setting readback differs: "+e.getKey(),"unknown");
        }
        after.addProperty("status","observed");after.addProperty("name",(String)a.invoke(name,value));after.add("fields",fields);
        after.addProperty("nativeVersion",Bitwig614Runtime.VERSION);after.addProperty("nativeJarSha256",a.actualHash());
        after.addProperty("scope","source_bound_launcher_clip_settings");after.addProperty("selectionSideEffect","none");
        after.addProperty("verificationScope","native_model_values_not_playback_or_save_reopen");return after;
    }
    private JsonObject read(Object value,Map<String,Object> properties){
        JsonObject r=new JsonObject();
        for(var e:properties.entrySet())if(e.getKey().equals("loopEnabled"))r.addProperty(e.getKey(),(Boolean)a.invoke(boolGet,e.getValue()));else r.addProperty(e.getKey(),(Double)a.invoke(doubleGet,e.getValue()));
        for(var e:getters.entrySet()) {Object v=a.invoke(e.getValue(),value);if(e.getKey().equals("nextAction"))r.addProperty(e.getKey(),(String)a.invoke(actionString,v));else if(v instanceof Boolean)r.addProperty(e.getKey(),(Boolean)v);else r.addProperty(e.getKey(),(Number)v);}
        return r;
    }
}
