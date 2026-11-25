package com.shinpstudio.navoverlay;

import android.content.Context;
import android.content.res.AssetManager;
import java.io.*;
import java.util.*;
import btools.router.*;

/** All route calculations and GPS coordinates stay on-device. */
final class EmbeddedRouter {
    private static final String ROOT = "brouter-1.7.10";
    private static String segment(Route.Point p) {
        int lon = (int)Math.floor(p.lon()/5)*5, lat = (int)Math.floor(p.lat()/5)*5;
        return (lon<0?"W":"E")+Math.abs(lon)+"_"+(lat<0?"S":"N")+Math.abs(lat)+".rd5";
    }
    private static void unpack(AssetManager assets, String folder, File target) throws IOException {
        if (!target.isDirectory() && !target.mkdirs()) throw new IOException("保存先を作成できません");
        String[] names = assets.list(folder);
        if(names==null||names.length==0) throw new IOException("経路データがありません: "+folder);
        for(String name:names){
            File out=new File(target,name);
            if(out.isFile()&&out.length()>0)continue;
            File tmp=new File(target,name+".part");
            try(InputStream in=assets.open(folder+"/"+name); OutputStream os=new FileOutputStream(tmp)){
                byte[] buf=new byte[65536]; int n; while((n=in.read(buf))!=-1)os.write(buf,0,n);
            }catch(IOException e){tmp.delete();throw e;}
            if(!tmp.renameTo(out)){tmp.delete();throw new IOException("データ展開に失敗: "+name);}
        }
    }
    static synchronized Route calculate(Context context,Route.Point from,Route.Point to,int mode)throws Exception{
        File base=new File(context.getFilesDir(),ROOT), profiles=new File(base,"profiles2"), segments=new File(base,"segments4");
        AssetManager assets=context.getAssets();
        // Use the APK's update timestamp as the extraction version, so updated map assets replace old ones.
        long apkTime=context.getPackageManager().getPackageInfo(context.getPackageName(),0).lastUpdateTime;
        File stamp=new File(base,"stamp-"+apkTime);
        if(!stamp.exists()){
            clear(profiles);clear(segments);
            unpack(assets,"brouter/profiles2",profiles);
            unpack(assets,"brouter/segments4",segments);
            if(!stamp.createNewFile())throw new IOException("展開状態を保存できません");
        }
        for(Route.Point p:List.of(from,to))if(!new File(segments,segment(p)).isFile())throw new IOException("対象地域の経路データがありません: "+segment(p));
        RoutingContext rc=new RoutingContext();
        rc.localFunction=new File(profiles,mode==0?"hiking-mountain.brf":"trekking.brf").getAbsolutePath();
        RoutingParamCollector params=new RoutingParamCollector();
        List<OsmNodeNamed> waypoints=params.readPositions(new double[]{from.lon(),to.lon()},new double[]{from.lat(),to.lat()});
        Map<String,String> options=new HashMap<>();options.put("timode","3");
        params.setParams(rc,waypoints,options);
        RoutingEngine engine=new RoutingEngine(null,null,segments,waypoints,rc,0);
        engine.quite=true;engine.doRun(60000L);
        if(engine.getErrorMessage()!=null)throw new IOException(engine.getErrorMessage());
        OsmTrack track=engine.getFoundTrack();if(track==null)throw new IOException("経路が見つかりません");
        return Route.parse(new FormatGpx(rc).format(track));
    }
    private static void clear(File dir)throws IOException{
        File[] children=dir.listFiles();if(children!=null)for(File f:children)if(!f.delete())throw new IOException("古いデータを削除できません");
    }
}
