# AREA: Area
Define an area of interest. 
When an aircraft exits the area, it is deleted from the simulation. It is possible to predefine shapes using the [[BOX]], [[CIRCLE]], [[POLY]] and [[POLYALT]] commands. 

This argument can be called in two ways: 
1. using the name of an already existing shape
2. as a square shape

Note that shapes/areas defined in the second way can be 2D (without altitude constraints) or 3D (with altitude constraints). 

**Usage:**

    AREA Shapename/OFF or AREA lat1,lon1,lat2,lon2,[top,bottom]

**Arguments 1:**

|Name|Type|Required|Description
|--------|------|---|---------------------------------------------------
Shapename| txt   | yes |Name of the predefined shape to be used as an area of interest. If 'Shapename' is 'OFF', then aircraft are no longer deleted if they leave the area of interest.|

**Arguments 2:**

|Name|Type|Required|Description
|--------|------|---|---------------------------------------------------
lat1| float | yes | [[latitude|Coordinates]] of the bottom left corner [deg] |
lon1| float | yes | [[longitude|Coordinates]] of the bottom left corner [deg] |
lat2| float | yes | [[latitude|Coordinates]] of the top right corner [deg]  |
lon2| float | yes | [[longitude|Coordinates]] of the top right corner [deg]  |
top| float  | no  | altitude of the top of area [ft] |
bottom| float | no | altitude of the bottom of area [ft] |


[[Back to command reference.|Command Reference]]