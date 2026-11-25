package com.shinpstudio.navoverlay;
import java.util.*;
public class RouteTest {
 static int passed=0;
 static void check(boolean b,String msg){if(!b)throw new AssertionError(msg);passed++;}
 static void reject(String s)throws Exception{try{Route.parse(s);throw new AssertionError("expected rejection");}catch(IllegalArgumentException ok){passed++;}}
 public static void main(String[] args)throws Exception {
 String gpx="""
 <?xml version="1.0"?><gpx xmlns="http://www.topografix.com/GPX/1/1">
 <rte><rtept lat="35" lon="139"><desc>start</desc><extensions><offset>0</offset></extensions></rtept>
 <rtept lat="35.001" lon="139"><desc>right</desc><extensions><turn>TR</turn><offset>1</offset></extensions></rtept>
 <rtept lat="35.001" lon="139.001"><desc>destination</desc><extensions><offset>2</offset></extensions></rtept></rte>
 <trk><trkseg><trkpt lat="35" lon="139"/><trkpt lat="35.001" lon="139"/><trkpt lat="35.001" lon="139.001"/></trkseg></trk></gpx>
 """;
 Route r=Route.parse(gpx);check(r.points.size()==3,"track count");check(r.turns.size()==2,"turn count");check(r.next(0).code().equals("TR"),"right turn");check(r.length()>190&&r.length()<210,"route length");
 Route.Match m=r.match(new Route.Point(35.0005,139),0,true);check(m.cross()<1,"on route");check(m.along()>54&&m.along()<57,"progress");check(r.at(1)-m.along()>54&&r.at(1)-m.along()<57,"remaining to turn");
 Route.Match after=r.match(new Route.Point(35.001,139.0005),m.along(),false);check(after.along()>r.at(1)+35,"turn passed");check(r.next(after.along()).code().equals("DEST"),"destination after turn");
 check(r.match(new Route.Point(35.0005,139.001),m.along(),false).cross()>50,"off route");
 check(Route.label("RNDB2").contains("2"),"roundabout");check(Route.arrow("TLU").equals("↶"),"u-turn");check(Route.label("EL").equals("左へ"),"exit");
 reject("segment not found");reject("<!DOCTYPE gpx SYSTEM 'file:///etc/passwd'><gpx/>");reject("<gpx><trk><trkseg><trkpt lat='35' lon='139'/></trkseg></trk></gpx>");
 try{new Route.Point(Double.NaN,139);throw new AssertionError();}catch(IllegalArgumentException ok){passed++;}
 Route loop=new Route(List.of(new Route.Point(35,139),new Route.Point(35.003,139),new Route.Point(35.003,139.001),new Route.Point(35,139)),List.of());check(loop.match(new Route.Point(35,139),0,true).along()<1,"loop start");check(loop.match(new Route.Point(35,139),loop.length()-15,false).along()>loop.length()-1,"loop finish");
 System.out.println("PASS: "+passed+" assertions");
 }
}
