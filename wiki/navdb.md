# The navdb object

The Navdatabase object serves as a global navigation database. It contains data on waypoints, airports, airways, flight information regions (FIRs), and ICAO country codes.  

## Waypoint/Navaid properties 
Name|Value|Description
----|-----|-----------
wpid|string|identifier
wplat|deg|latitude
wplon|deg|longitude
wptype|string|type: VOR, NDB, ILS, LOC, GS, OM, MM, IM, DME, TACAN, or FIX
wpelev|m|elevation
wpvar|deg|magnetic variation
wpfreq|kHz/MHz|Navaid frequency kHz(NDB) or MHz(VOR)
wpdesc|string|description

## Airway (legs) properties
Name|Value|Description
----|-----|-----------
awid|string|identifier
awfromwpid|string|from waypoint identifier 
awfromlat|deg|from waypoint latitude
awfromlon|deg|from waypoint longitude
awtowpid|string|to waypoint identifier
awtolat|deg|to waypoint latitude
awtolon|deg|to waypoint longitude
awndir|1 or 2|number of directions
awlowfl|int|lowest flight level
awupfl|int|highest flight level

## Airport properties
Name|Value|Description
----|-----|-----------
apid|string|ICAO 4-character identifier
apname|string|full name
aplat|deg|latitude
aplon|deg|longitude
apmaxrwy|m|max runway length
aptype|1, 2, or 3|type: 1=large, 2=medium, 3=small
apco|string|2-character country code
apelev|m|field elevation above mean sea level

## FIR properties
Name|Value|Description
----|-----|-----------
fir|string|FIR name
firlat0|deg|start latitude of a line of border
firlon0|deg|start longitude of a line of border
firlat1|deg|end latitude of a line of border
firlon1|deg|end longitude of a line of border

## ICAO country code data
Name|Value|Description
----|-----|-----------
coname|string|full name
cocode2|string|2-character (A2) code
cocode3|string|3-character (A3) code
conr|integer|country number

## Runway threshold data
Name|Value|Description
----|-----|-----------
rwythresholds|-|runway threshold data: ICAO airport code, runway identifier, latitude, longitude, bearing