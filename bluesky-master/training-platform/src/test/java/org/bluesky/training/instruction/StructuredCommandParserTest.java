package org.bluesky.training.instruction;

import org.junit.jupiter.api.Test;
import org.bluesky.training.common.V2DomainException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StructuredCommandParserTest {
    @Test void fractionalIntegerParameterReturnsDomainError() {
        V2DomainException e=assertThrows(V2DomainException.class,()->StructuredCommandParser.parse("IDENT",Collections.singletonMap("durationSeconds",1.5),null,0));
        assertEquals(400,e.httpStatus());
    }
    @Test void resumeAutoDoesNotBecomeNavigationPoint() {
        Map<?,?> p=(Map<?,?>)new CommandTextParser().parse("RESUME AUTO",null,0).get("parameters");
        assertNull(p.get("resumePoint"));
    }
    @Test void rejectsInvalidSquawkBeforeBusinessFieldsAreWritten() {
        V2DomainException e=assertThrows(V2DomainException.class,()->StructuredCommandParser.parse("SQK",Collections.singletonMap("squawk","8888"),null,600));
        assertEquals("INVALID_INSTRUCTION",e.code());
    }
    @Test void structuredSpeedUsesTextEnvelopeLimits() {
        assertThrows(V2DomainException.class,()->StructuredCommandParser.parse("SPD",Collections.singletonMap("indicatedAirspeedKt",999),null,600));
        assertEquals(240.0,StructuredCommandParser.parse("SPD",Collections.singletonMap("indicatedAirspeedKt",240),null,600).get("indicatedAirspeedKt"));
    }
    @Test void relativeTakeoffUsesCurrentSimulationTime() {
        Map<String,Object> command=new CommandTextParser().parse("TAKEOFF 02L AT T+60 LEVEL 9000","ZBAA",600);
        assertEquals(660.0,((Map<?,?>)command.get("parameters")).get("scheduledTimeSeconds"));
    }
    @Test void clockRejectsInvalidHoursAndDoesNotRollHhmmIntoTomorrow() {
        assertThrows(V2DomainException.class,()->ProcedureCommandParsers.parseSimulationTime("D0T25:00:00",0));
        assertThrows(V2DomainException.class,()->ProcedureCommandParsers.parseSimulationTime("0800",90000+36000));
        assertEquals(86400.0+43200,ProcedureCommandParsers.parseSimulationTime("1200",86400+36000).get("targetTimeSeconds"));
    }
    @Test void checksumIncludesNestedParametersAndIgnoresJsonKeyOrder() {
        Map<String,Object> a=new LinkedHashMap<>();a.put("parameters_json","{\"altitude\":9000,\"speed\":240}");
        Map<String,Object> b=new LinkedHashMap<>();b.put("parameters_json","{\"speed\":240,\"altitude\":9000}");
        assertEquals(SpecialProfileSchemaRegistry.canonicalizeAndChecksum(a),SpecialProfileSchemaRegistry.canonicalizeAndChecksum(b));
        b.put("parameters_json","{\"altitude\":10000,\"speed\":240}");
        assertNotEquals(SpecialProfileSchemaRegistry.canonicalizeAndChecksum(a),SpecialProfileSchemaRegistry.canonicalizeAndChecksum(b));
    }
}
