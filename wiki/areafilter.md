# Area Filter 

The [areafilter.py](https://github.com/TUDelft-CNS-ATM/bluesky/blob/master/bluesky/tools/areafilter.py) module (areafilter.py) is part of the set of BlueSky tools. This module is used to define an [AREA](https://github.com/TUDelft-CNS-ATM/bluesky/wiki/AREA) or to [DELETE ](https://github.com/TUDelft-CNS-ATM/bluesky/wiki/DEL) an area of a shape; for example [BOX](https://github.com/TUDelft-CNS-ATM/bluesky/wiki/BOX), [CIRCLE](https://github.com/TUDelft-CNS-ATM/bluesky/wiki/CIRCLE), [POLY](https://github.com/TUDelft-CNS-ATM/bluesky/wiki/POLY) and [POLYALT](https://github.com/TUDelft-CNS-ATM/bluesky/wiki/POLYALT). New area shapes can also be created using the area filter module.

The area filter tool can be imported using:

```python
...
from bluesky.tools import areafilter
...
```
The areafilter module contains four functions:
* _hasArea(areaname)_
* _defineArea(areaname, areatype, coordinates, top, bottom)_
* _checkInside(areaname, lat, lon, alt)_
* _deleteArea(areaname)_

The remainder of this webpage explains the above enlisted functions of the areafilter module.

The areafilter.hasArea function checks whether an area with name "areaname'' exists.

The areafilter.defineArea function is used to define a new area such as a box, circle, polygonal etc. The arguments used by this function include, the area name (any name you wish), area type (that has been previously defined), lat/lon coordinates of the area, the top/bottom bound of area (if area is 3D). More areas can be defined by initiating classes. 

The areafilter module contains other useful functions such as the areafilter.checkInside function. This function checks if the respective coordinates are inside of the defined area. The input used in this function include: area name, lat/lon and altitude. The output of the function is an array of binary values. This can be useful for checking if traffic is inside an area; for example a sector. 

Lastly, the areafilter.deleteArea function can be used to delete an area with name "areaname''. 










