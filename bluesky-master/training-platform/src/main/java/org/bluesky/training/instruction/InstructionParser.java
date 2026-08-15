package org.bluesky.training.instruction;

import org.bluesky.training.configuration.FieldValidationException;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.Locale;

@Component
public class InstructionParser {
    public ParsedInstruction parse(String text, String callsign, double currentAltitudeFeet) {
        String normalized = text == null ? "" : text.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        String[] tokens = normalized.split(" ");
        if (tokens.length == 2 && "HDG".equals(tokens[0])) {
            try {
                double heading = Double.parseDouble(tokens[1]);
                if (heading < 0 || heading > 360) throw new NumberFormatException();
                return new ParsedInstruction(normalized,
                        new EngineInstructionCommand(callsign, "HDG", heading, null, null,
                                null, null, null, Collections.emptyList()));
            } catch (NumberFormatException exception) {
                throw invalid();
            }
        }
        if ((tokens.length == 2 || tokens.length == 4) && "ALT".equals(tokens[0])) {
            try {
                double altitudeFeet = Double.parseDouble(tokens[1]);
                if (altitudeFeet < 0) throw new NumberFormatException();
                Double verticalSpeed = null;
                if (tokens.length == 4) {
                    if (!"VS".equals(tokens[2])) throw new NumberFormatException();
                    double magnitude = Math.abs(Double.parseDouble(tokens[3]));
                    if (magnitude <= 0) throw new NumberFormatException();
                    verticalSpeed = altitudeFeet < currentAltitudeFeet ? -magnitude : magnitude;
                }
                return new ParsedInstruction(normalized,
                        new EngineInstructionCommand(callsign, "ALT", null, altitudeFeet,
                                verticalSpeed, null, null, null, Collections.emptyList()));
            } catch (NumberFormatException exception) {
                throw new FieldValidationException("text", "高度指令格式为 ALT 12000 或 ALT 12000 VS 1000");
            }
        }
        if (tokens.length == 2 && ("SPD".equals(tokens[0]) || "MACH".equals(tokens[0]))) {
            try {
                double value = Double.parseDouble(tokens[1]);
                if ("SPD".equals(tokens[0])) {
                    if (value <= 0) throw new NumberFormatException();
                    return new ParsedInstruction(normalized,
                            new EngineInstructionCommand(callsign, "SPD", null, null, null,
                                    value, null, null, Collections.emptyList()));
                }
                if (value <= 0 || value >= 1.0) throw new NumberFormatException();
                return new ParsedInstruction(normalized,
                        new EngineInstructionCommand(callsign, "MACH", null, null, null,
                                null, value, null, Collections.emptyList()));
            } catch (NumberFormatException exception) {
                throw new FieldValidationException("text", "速度指令格式为 SPD 250 或 MACH 0.78");
            }
        }
        if (tokens.length == 2 && "DCT".equals(tokens[0])) {
            return new ParsedInstruction(normalized,
                    new EngineInstructionCommand(callsign, "DCT", null, null, null,
                            null, null, tokens[1], Collections.emptyList()));
        }
        if (tokens.length >= 2 && "RTE".equals(tokens[0])) {
            List<String> route = Arrays.stream(tokens).skip(1).collect(Collectors.toList());
            return new ParsedInstruction(normalized,
                    new EngineInstructionCommand(callsign, "RTE", null, null, null,
                            null, null, null, route));
        }
        throw invalid();
    }

    private FieldValidationException invalid() {
        return new FieldValidationException("text", "首版航向指令格式为 HDG 090");
    }

    public static final class ParsedInstruction {
        private final String normalizedText;
        private final EngineInstructionCommand command;

        public ParsedInstruction(String normalizedText, EngineInstructionCommand command) {
            this.normalizedText = normalizedText;
            this.command = command;
        }

        public String getNormalizedText() { return normalizedText; }
        public EngineInstructionCommand getCommand() { return command; }
    }
}
