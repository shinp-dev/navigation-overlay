package com.shinpstudio.navoverlay;

import java.io.StringReader;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

/** Pure Java routing geometry and GPX instruction reader. */
public final class Route {
    public record Point(double lat, double lon) {
        public Point { if (!Double.isFinite(lat)||!Double.isFinite(lon)||Math.abs(lat)>90||Math.abs(lon)>180) throw new IllegalArgumentException("座標が不正です"); }
    }
    public record Turn(int index, String code) {}
    public record Match(double along, double cross, int segment) {}
    public final List<Point> points;
    public final List<Turn> turns;
    final double[] dist;
    public Route(List<Point> points, List<Turn> turns) {
        if(points.size()<2) throw new IllegalArgumentException("経路が短すぎます");
        this.points=List.copyOf(points); dist=new double[points.size()];
        for(int i=1;i<dist.length;i++) dist[i]=dist[i-1]+distance(points.get(i-1),points.get(i));
        ArrayList<Turn> sorted=new ArrayList<>();
        for(Turn t:turns) if(t.index>=0&&t.index<points.size()-1&&!t.code.isBlank()) sorted.add(t);
        sorted.sort(Comparator.comparingInt(Turn::index)); sorted.add(new Turn(points.size()-1,"DEST")); this.turns=List.copyOf(sorted);
    }
    public double length(){return dist[dist.length-1];}
    public double at(int index){return dist[index];}
    static double deltaLon(double a,double b){return ((a-b+540)%360)-180;}
    public static double distance(Point a,Point b){double p=Math.toRadians(b.lat-a.lat), q=Math.toRadians(deltaLon(b.lon,a.lon));double h=Math.sin(p/2)*Math.sin(p/2)+Math.cos(Math.toRadians(a.lat))*Math.cos(Math.toRadians(b.lat))*Math.sin(q/2)*Math.sin(q/2);return 12742000*Math.asin(Math.sqrt(Math.min(1,h)));}
    public Match match(Point p,double previous,boolean initial){
        Match best=null; double score=Double.POSITIVE_INFINITY;
        double scale=111195*Math.cos(Math.toRadians(p.lat));
        for(int i=0;i<points.size()-1;i++){
            if(!initial&&(dist[i+1]<previous-35||dist[i]>previous+220))continue;
            Point a=points.get(i),b=points.get(i+1);double ax=deltaLon(a.lon,p.lon)*scale,ay=(a.lat-p.lat)*111195,bx=deltaLon(b.lon,p.lon)*scale,by=(b.lat-p.lat)*111195;
            double dx=bx-ax,dy=by-ay,den=dx*dx+dy*dy;double t=den<1e-8?0:Math.max(0,Math.min(1,-(ax*dx+ay*dy)/den));
            double cross=Math.hypot(ax+t*dx,ay+t*dy),along=dist[i]+t*(dist[i+1]-dist[i]);double s=cross+(initial?0:Math.max(0,Math.abs(along-previous)-25)*0.12);
            if(s<score){score=s;best=new Match(along,cross,i);}
        }
        if(best==null)return match(p,previous,true);return best;
    }
    public Turn next(double progress){for(Turn t:turns)if(t.code.equals("DEST")||at(t.index)>progress-8)return t;return turns.get(turns.size()-1);}
    public static Route parse(String gpx) throws Exception {
        if(gpx==null||!gpx.contains("<gpx"))throw new IllegalArgumentException(gpx==null?"BRouterから応答なし":gpx.substring(0,Math.min(300,gpx.length())));
        if(gpx.contains("<!DOCTYPE")||gpx.contains("<!ENTITY"))throw new IllegalArgumentException("外部エンティティ不可");
        DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();f.setExpandEntityReferences(false);f.setNamespaceAware(true);
        var builder=f.newDocumentBuilder();builder.setEntityResolver((a,b)->new InputSource(new StringReader("")));
        Document doc=builder.parse(new InputSource(new StringReader(gpx)));
        NodeList track=doc.getElementsByTagNameNS("*","trkpt");ArrayList<Point> pts=new ArrayList<>();for(int i=0;i<track.getLength();i++){Element e=(Element)track.item(i);pts.add(new Point(Double.parseDouble(e.getAttribute("lat")),Double.parseDouble(e.getAttribute("lon"))));}
        ArrayList<Turn> ts=new ArrayList<>();NodeList route=doc.getElementsByTagNameNS("*","rtept");
        for(int i=0;i<route.getLength();i++){Element e=(Element)route.item(i);String turn=child(e,"turn"),offset=child(e,"offset");if(!turn.isBlank()&&!offset.isBlank())ts.add(new Turn(Integer.parseInt(offset.trim()),turn.trim().toUpperCase(Locale.ROOT)));}
        if(route.getLength()<2)throw new IllegalArgumentException("右左折案内を取得できません。BRouterを更新してください");
        return new Route(pts,ts);
    }
    private static String child(Element e,String name){NodeList n=e.getElementsByTagNameNS("*",name);return n.getLength()==0?"":n.item(0).getTextContent();}
    public static String label(String code){return switch(code){case "TL","TSLL","TSHL","KL","EL"->"左へ";case "TR","TSLR","TSHR","KR","ER"->"右へ";case "TU","TRU","TLU"->"Uターン";case "C"->"直進";case "BL","OFFR"->"道を確認";case "DEST"->"目的地";default->code.matches("RN(DB|LB)\\d+")?"環状交差点 "+code.replaceAll("[^0-9]","")+"番出口":"分岐を確認";};}
    public static String arrow(String code){return switch(code){case "TL","TSLL","TSHL","KL","EL"->"←";case "TR","TSLR","TSHR","KR","ER"->"→";case "TU","TRU","TLU"->"↶";case "DEST"->"◎";case "C"->"↑";default->"◇";};}
}
