package dev.bitwigagent;

import com.bitwig.extension.controller.api.*;
import com.bitwig.extension.callback.*;
import com.google.gson.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

/** Simulated queues and native adapter; does not establish host binding behavior. */
public final class LauncherSettingsModuleTest {
    static final String ID="11111111-1111-1111-1111-111111111111";
    static int checks;
    public static void main(String[] args){
        JsonObject p=target();LauncherSettingsModule.validateParams("launcher.get",p);
        bad("launcher.set",p);
        for(String field:List.of("loopEnabled","nextActionEnabled","nextActionLinkToLoop")){JsonObject b=target();b.addProperty(field,1);bad("launcher.set",b);}
        for(String field:List.of("playStartBeats","playStopBeats","loopStartBeats","loopLengthBeats","nextActionDelayBeats")){JsonObject b=target();b.addProperty(field,-1);bad("launcher.set",b);}
        for(String field:List.of("playStartBeats","playStopBeats","loopStartBeats","loopLengthBeats")){JsonObject b=target();b.addProperty(field,16);bad("launcher.set",b);}
        JsonObject readOnly=target();readOnly.addProperty("loopEnabled",true);bad("launcher.set",readOnly);
        JsonObject b=target();b.addProperty("nextAction","random");bad("launcher.set",b);
        b=target();b.addProperty("nextActionLoopCount",65);bad("launcher.set",b);
        b=target();b.addProperty("playStartBeats",8);b.addProperty("playStopBeats",4);bad("launcher.set",b);
        Fixture f=new Fixture();JsonObject settings=target();settings.addProperty("nextAction","play_next");settings.addProperty("nextActionLoopCount",1);
        check(f.call("launcher.set",settings).get("nextAction").getAsString().equals("play_next"),"source-bound write read back");
        check(f.writes==1,"one write");f.call("launcher.get",target());check(f.writes==1,"read does not write");
        b=target();b.addProperty("expectedClipName","Wrong");expect("stale_context",f.start("launcher.get",b));check(f.calls==2,"wrong name rejected before native access");
        Fixture fail=new Fixture();fail.failWrite=true;HostOperation failed=fail.start("launcher.set",settings);fail.drain();expect("internal_error",failed);
        expect("write_quarantined",fail.start("launcher.set",settings));fail.call("launcher.get",target());check(fail.writes==1,"unknown write stays quarantined but reads allowed");
        Fixture late=new Fixture();HostOperation op=late.start("launcher.set",settings);op.result.completeExceptionally(new RuntimeException("cancel"));late.drain();check(late.writes==0,"cancelled queued native operation does not write");
        Fixture unsafe=new Fixture(true);expect("unsafe_state",unsafe.start("launcher.set",settings));check(unsafe.calls==0,"playing blocks settings write");
        System.out.println("LauncherSettingsModuleTest: "+checks+" assertions (simulated queues only)");
    }
    static JsonObject target(){JsonObject p=new JsonObject();p.addProperty("trackId",ID);p.addProperty("slotIndex",0);p.addProperty("expectedClipName","Verse");return p;}
    static void bad(String m,JsonObject p){try{LauncherSettingsModule.validateParams(m,p);throw new AssertionError("accepted invalid settings");}catch(RpcException e){check(e.code.equals("invalid_params"),"invalid params");}}
    static void check(boolean x,String text){checks++;if(!x)throw new AssertionError(text);}
    static void expect(String code,HostOperation op){try{op.result.join();throw new AssertionError("expected "+code);}catch(CompletionException e){check(e.getCause() instanceof RpcException&&((RpcException)e.getCause()).code.equals(code),"error "+code+": "+e.getCause());}}
    static final class Fixture {
        final ArrayDeque<Runnable> queue=new ArrayDeque<>();final LauncherSettingsModule module;int writes,calls;boolean failWrite,playing;
        Fixture(){this(false);}Fixture(boolean playing){
            this.playing=playing;
            Node host=new Node(ControllerHost.class,"host");host.fixture=this;
            HostServices services=new HostServices((ControllerHost)host.proxy);
            module=new LauncherSettingsModule(services,new LauncherSettingsModule.Access(){
                public void execute(Runnable task){queue.add(task);}
                public JsonObject apply(Object slot,String id,String name,JsonObject changes,Runnable guard,Runnable before){
                    calls++;guard.run();if(!changes.entrySet().isEmpty()){before.run();writes++;if(failWrite)throw new RuntimeException("native exception");}
                    JsonObject out=changes.deepCopy();out.addProperty("status","observed");return out;
                }
            });
        }
        HostOperation start(String m,JsonObject p){HostOperation op=new HostOperation(System.nanoTime()+TimeUnit.SECONDS.toNanos(5));try{module.validate(m,p);module.dispatch(op,m,p);}catch(RuntimeException e){op.fail(e);}return op;}
        void drain(){while(!queue.isEmpty())queue.remove().run();}
        JsonObject call(String m,JsonObject p){HostOperation op=start(m,p);drain();return op.result.join();}
        final class Node implements InvocationHandler {
            final Object proxy;final String path;Fixture fixture;final Map<String,Node> children=new HashMap<>();
            Node(Class<?> type,String path){this.path=path;proxy=Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},this);}
            Object val(Class<?> type){
                if(type==boolean.class||type==Boolean.class)return path.endsWith("exists")||path.endsWith("hasContent")||(path.endsWith("isPlaying")&&playing);
                if(type==String.class)return path.endsWith("channelId")?ID:path.endsWith("name")?"Verse":"";
                if(type==int.class||type==Integer.class)return path.endsWith("itemCount")?1:0;
                if(type==double.class||type==Double.class)return 0d;
                return null;
            }
            public Object invoke(Object proxy,Method m,Object[] args)throws Throwable{
                String n=m.getName();if(n.equals("toString"))return path;if(n.equals("hashCode"))return System.identityHashCode(proxy);if(n.equals("equals"))return proxy==args[0];
                if(n.equals("scheduleTask")){queue.add((Runnable)args[0]);return null;}
                if(n.equals("addValueObserver")){
                    Object cb=args[0];if(cb instanceof BooleanValueChangedCallback)((BooleanValueChangedCallback)cb).valueChanged((Boolean)val(boolean.class));
                    else if(cb instanceof StringValueChangedCallback)((StringValueChangedCallback)cb).valueChanged((String)val(String.class));
                    else if(cb instanceof IntegerValueChangedCallback)((IntegerValueChangedCallback)cb).valueChanged((Integer)val(int.class));return null;
                }
                Class<?> returns=m.getReturnType();if(n.equals("getItemAt")){if(path.endsWith("createTrackBank"))returns=Track.class;else if(path.endsWith("createDeviceBank"))returns=Device.class;else if(path.endsWith("createLayerBank"))returns=DeviceLayer.class;else if(path.endsWith("clipLauncherSlotBank"))returns=ClipLauncherSlot.class;}
                if(returns.isInterface()){final Class<?> childType=returns;String key=n+(n.equals("getItemAt")?args[0]:"");return children.computeIfAbsent(key,k->new Node(childType,path+"/"+key)).proxy;}
                return val(returns);
            }
        }
    }
}
