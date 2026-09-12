package org.bluesky.training.instruction;

import org.bluesky.training.common.V2DomainException;
import java.util.*;

/** Both HTTP command forms use the same syntax and range validators. */
public final class StructuredCommandParser {
    private StructuredCommandParser() {}

    public static Map<String,Object> parse(String type, Map<String,Object> p,
                                           String destination, double now) {
        try { return parseValidated(type,p,destination,now); }
        catch (NumberFormatException invalid) { throw invalid("parameters"); }
    }

    private static Map<String,Object> parseValidated(String type, Map<String,Object> p,
                                           String destination, double now) {
        if (p == null) throw invalid("parameters");
        String text;
        switch (type) {
            case "HDG": text="HDG "+optional(p,"turnDirection")+value(p,"magneticHeadingDeg"); break;
            case "LEFT": case "RIGHT":
                String angle=value(p,"valueDeg");
                if ("ABSOLUTE".equals(p.get("mode"))) angle=String.format(Locale.ROOT,"%03d",Integer.parseInt(angle));
                else if (!"RELATIVE".equals(p.get("mode"))) throw invalid("mode");
                text=type+" "+angle; break;
            case "ALT": text="ALT "+value(p,"altitudeFtMsl")+"FT"+(p.get("verticalRateFpm")==null?"":" VS "+value(p,"verticalRateFpm")+"FPM"); break;
            case "VS": text="VS "+signed(p,"verticalRateFpm"); break;
            case "SPD": text="SPD "+value(p,"indicatedAirspeedKt")+"KT"; break;
            case "MACH": text="MACH "+value(p,"mach"); break;
            case "DCT": text="DCT "+value(p,"targetPoint"); break;
            case "RTE":
                if (!(p.get("route") instanceof List)) throw invalid("route");
                text="RTE "+String.join(" ", ((List<?>)p.get("route")).stream().map(String::valueOf).toArray(String[]::new)); break;
            case "RESUME": text="RESUME "+optional(p,"resumePoint"); break;
            case "ORBIT": text="EXIT".equals(p.get("action"))?"ORBIT EXIT":"ORBIT "+optional(p,"centerPoint")+value(p,"turnDirection")+" "+value(p,"radiusNm"); break;
            case "HOLD":
                text="EXIT".equals(p.get("action"))?"HOLD EXIT":p.get("procedureName")!=null?"HOLD "+value(p,"procedureName"):
                    "HOLD "+value(p,"fixPoint")+" "+value(p,"turnDirection")+" "+value(p,"inboundMagneticHeadingDeg")+" "+(p.get("legNm")!=null?value(p,"legNm")+"NM":value(p,"legSeconds")); break;
            case "OFFSET": text="CLEAR".equals(p.get("action"))?"OFFSET CLR":"OFFSET "+value(p,"side")+" "+value(p,"distanceNm"); break;
            case "VOR": text="VOR "+value(p,"station")+" "+value(p,"direction")+" "+value(p,"radialDeg")+(p.get("dmeDistanceNm")==null?"":" DME "+value(p,"dmeDistanceNm")); break;
            case "SIDSTAR": text="SIDSTAR "+value(p,"procedureId")+" "+optional(p,"reportPoint"); break;
            case "P_LEVEL": text="P_LEVEL "+value(p,"legPoint")+" "+value(p,"altitudeFtMsl")+"FT"; break;
            case "P_TIME":
                Map<String,Object> time=ProcedureCommandParsers.parseLegLevel("P_LEVEL "+value(p,"legPoint")+" 1000",Collections.emptyList());
                time.remove("altitudeFtMsl"); time.put("conflictKeyTemplate","BUSINESS_FIELD:LEG:{legId}:TIME");
                double target=number(p,"targetTimeSeconds"); if(target<=now) throw invalid("targetTimeSeconds");
                time.put("targetTimeSeconds",target); time.put("format","ABSOLUTE_SECONDS"); return time;
            case "TAKEOFF":
                Map<String,Object> takeoff=LandingCommandParsers.parseTakeoff("TAKEOFF "+value(p,"runway")+" LEVEL "+value(p,"targetAltitudeFtMsl"),Collections.emptyList(),now);
                if(p.get("scheduledTimeSeconds")!=null) { double due=number(p,"scheduledTimeSeconds"); if(due<now)throw invalid("scheduledTimeSeconds"); takeoff.put("scheduledTimeSeconds",due); } return takeoff;
            case "ILS": text="ILS "+optional(p,"airportCode")+value(p,"runway")+" "+value(p,"turnDirection")+" "+value(p,"interceptMagneticHeadingDeg"); break;
            case "MISSED": text="MISSED "+optional(p,"missedProcedureId"); break;
            case "SQK": text="SQK "+("CLEAR".equals(p.get("action"))?"CLR":value(p,"squawk")); break;
            case "SSRMODE": text="SSRMODE "+value(p,"ssrMode"); break;
            case "IDENT": return TransponderCommandParsers.buildIdent(p.get("durationSeconds")==null?null:Integer.valueOf(value(p,"durationSeconds")));
            case "NML": case "NSPEED": text=type; break;
            case "ID": case "DECOMP": text=type+" "+value(p,"mode"); break;
            default: throw invalid("type");
        }
        return (Map<String,Object>)new CommandTextParser().parse(text.trim(),destination,now).get("parameters");
    }
    private static String optional(Map<String,Object> p,String key) {return p.get(key)==null?"":value(p,key)+" ";}
    private static String value(Map<String,Object> p,String key) {
        Object v=p.get(key); if(v==null)throw invalid(key);
        String s=v instanceof Number?new java.math.BigDecimal(v.toString()).stripTrailingZeros().toPlainString():String.valueOf(v);
        if(s.isEmpty() || s.matches(".*\\s+.*"))throw invalid(key); return s;
    }
    private static double number(Map<String,Object> p,String key) {try {double n=Double.parseDouble(value(p,key));if(!Double.isFinite(n))throw invalid(key);return n;} catch(NumberFormatException e){throw invalid(key);}}
    private static String signed(Map<String,Object> p,String key) {double n=number(p,key);return n==0?"0":(n>0?"+":"")+value(p,key);}
    private static V2DomainException invalid(String key){return new V2DomainException("INVALID_INSTRUCTION",400,"结构化指令参数非法: "+key,Collections.singletonList(key));}
}
