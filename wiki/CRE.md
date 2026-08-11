# CRE: Cre
Create an aircraft at specified coordinates.

For creating multiple randomly located aircraft, see [[MCRE]].

**Usage:**

    CRE acid,type,lat,lon,hdg,alt,spd

**Arguments:**

|Name|Type|Required|Description
|--------|------|---|---------------------------------------------------
acid|txt|yes| Unique aircraft callsign
type|txt|yes| [ICAO aircraft type designator](https://en.wikipedia.org/wiki/List_of_ICAO_aircraft_type_designators)
lat|float|yes| [Latitude](https://github.com/TUDelft-CNS-ATM/bluesky/wiki/Coordinates)
lon|float|yes| [Longitude](https://github.com/TUDelft-CNS-ATM/bluesky/wiki/Coordinates)
hdg|float|yes| Heading [deg] [1]
alt|float/txt|yes| [Altitude](https://github.com/TUDelft-CNS-ATM/bluesky/wiki/Altitude) [2]
spd|float|yes| [Aircraft speed](https://github.com/TUDelft-CNS-ATM/bluesky/wiki/Speed)

[1] The heading of the aircraft can be given by clicking the screen with the mouse. The aircraft will fly from its start coordinates, heading towards the clicked location.

[2] Altitude can be given as altitude [ft] or flight level [-]. In order to give the altitude as flight level, use the letters "FL".

**Examples**

* _CRE acid type lat lon heading altitude speed_
* CRE KL204 B744 52.3836 4.0967 180 25000 200
* CRE KL204 B744 52'23"1 4'5"48 180 FL250 .49
* CRE KL204 B744 _click       click_ 25000 200

[[Back to command reference.|Command Reference]]
