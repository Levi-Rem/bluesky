# BlueSky optimization
To optimize execution time, BlueSky uses numpy vector and matrix operations as often as possible. As a consequence, in BlueSky, simulated entities such as aircraft are not represented by a list of _objects with properties_ (lat, lon, alt, ...), but by multiple _vectors of aircraft properties_. In code, this results in the following difference:

**Example A: With aircraft as objects**
```python
from math import *
from aircraft import Aircraft
# Initialization of aircraft data
aircraft = [Aircraft(...), Aircraft(...), ..., Aircraft(...)]

# Calculate distance from reference point
Rearth = 6371000.0
reflat, reflon = 0, 0
dx, dy = [], []
for ac in aircraft:
    dx.append(Rearth * (reflon - ac.lon) * cos(0.5 * (reflat + ac.lat))
    dy.append(Rearth * (reflat - ac.lat))
```

**Example B: With aircraft represented by multiple vectors of aircraft properties**
```python
import numpy as np
# Initialization of aircraft data
lat = np.array([lat0, lat1, ..., latn])
lon = np.array([lon0, lon1, ..., lonn])
...

# Calculate distance from reference point
Rearth = 6371000.0
reflat, reflon = 0, 0
dx = Rearth * (reflon - lon) * np.cos(0.5 * (reflat + lat))
dy = Rearth * (reflat - lat)
```

The difference in terms of execution speed is that in example A, the entire loop is performed in python, and typechecking is done for each variable in each iteration pass. In example B, the entire mathematical expressions in the last two lines are evaluated low-level inside numpy. This results in a massive speedup of the simulation.
