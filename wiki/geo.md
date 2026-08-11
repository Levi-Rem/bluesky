The [geo.py](https://github.com/TUDelft-CNS-ATM/bluesky/blob/master/bluesky/tools/geo.py) module is a BlueSky tool that defines a set of standard geographic functions and constants for coding in BlueSky. The tool can be imported using:

```python 
...
from bluesky.tools import geo
...
```


Some standard geographic functions available include:

1. Calculating the earth's radius \[m\] with the World Geodetic System [WGS'84](https://en.wikipedia.org/wiki/World_Geodetic_System) geoid definition. The input used in this function is the latitude \[deg\]. External [reference](http://www.movable-type.co.uk/scripts/latlong.html?from=49.1715000,-121.7493500&to=49.18258,-121.75441). 

### Example 1:
```python 
...
IN: lat1 = 53 
# call function and compute earth's radius using WGS84 definition 
Radius = rwgs84(lat1)
print(Radius)
OUT: 6364538.974408425
...
```
2. Calculating the bearing and distance using WGS84 definition.

### Example 2:
```python 
...
IN: 45, 12, 52, 2 (latd1,lond1, latd2, lond2)
# call function and compute the bearing and distance \[nm\] 
qdr, dist = qdrdist(45, 12, 52, 2 )

print(qdr,dist)
OUT: -39.76323481565359 577.2634174193117
...
```
3. Calculating the distance using the [haversine](https://en.wikipedia.org/wiki/Haversine_formula) formulae with input lat/lon in degrees and major semi-axis (a = 6378137.0). An example is given below:

### Example 3: 
```python
...
IN: 45, 12, 52, 2 (latd1,lond1, latd2, lond2)
# call function and compute distance \[m\] 
d = latlondist(45,12,52,2)
print(d)
OUT: 1069091.8490605652
...
```
4. Calculating the gravity acceleration at a given latitude according to [WGS'84](https://en.wikipedia.org/wiki/World_Geodetic_System) definition with input: gravity acceleration at the equator, which is 9.7803m/s^2; the earth's eccentricity, which is around 6.694e-3; and a constant k (0.001932), which is derived from the flattening. An example of this equation is shown below:

```python 
...
IN: 45 (latd)
# call function and compute gravity acceleration for the given latitude \[deg\]
gravity = wgsg(45)
print(gravity)
OUT: 9.806172153520823
...
```
There are also some useful formula for getting some fast approximations; for example the reference position of your aircraft and distance. More information on this can be found in [geo.py](https://github.com/TUDelft-CNS-ATM/bluesky/blob/master/bluesky/tools/geo.py). 





