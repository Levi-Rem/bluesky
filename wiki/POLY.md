# POLY: Poly
Draw a random polygon on the radar display with a user defined name. This polygon can be made into an area of interest using the [[AREA]] command. 

NOTE:
* Polygons can be composed of an unlimited number of corners
* Only 2D polygons can be made with [[POLY]]
* For the 3D polygons, use [[POLYALT]]

**Usage:**

    POLY name,lat1,lon1,lat1,lon1, ...

**Arguments:**

|Name|Type|Required|Description|
|--------|------|---|---------------------------------------------------
name| txt | yes |  name of the polygon |
lat1|  float   | yes  | [[latitude|Coordinates]] of first corner of polygon [deg] |
lon1|  float   | yes  | [[longitude|Coordinates]] of first corner of polygon [deg] |
lat2|  float   | yes  | [[latitude|Coordinates]] of second corner of polygon [deg] |
lon2|  float   | yes  | [[longitude|Coordinates]] of second corner of polygon [deg] |

[[Back to command reference.|Command Reference]]