# POLYALT: Polyalt
Draw a random polygon with altitude constraints on the radar display with a user defined name. This polygon can be made into an area of interest using the [[AREA]] command. 

**Usage:**

    POLY name,top,bottom,lat,lon,lat,lon, ...

**Arguments:**

|Name|Type|Required|Description
|--------|------|---|---------------------------------------------------
name| txt | yes |  name of the polygon |
top|   float  | no   | [[altitude]] of the top of box |
bottom| float | no   | [[altitude]] of the bottom of box |
lat1|  float   | yes  | [[latitude|Coordinates]] of first corner of polygon |
lon1|  float   | yes  | [[longitude|Coordinates]] of first corner of polygon |
lat2|  float   | yes  | [[latitude|Coordinates]] of second corner of polygon |
lon2|  float   | yes  | [[longitude|Coordinates]] of second corner of polygon |


[[Back to command reference.|Command Reference]]
