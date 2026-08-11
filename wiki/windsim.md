# WindSim object

The WindSim object allows to simulate the wind conditions, such as wind speed and wind direction at a given position and altitude. WindSim inherits from its parent class Windfield and has the following properties and methods:

## WindSim properties
|Name|Value|Description
|----|------|-----------------
altmax|13,716 m|maximum altitude of 45000 ft
altstep|30.48 m|altitude step of 100 ft (flight level)
altaxis|-|array of altitudes from 0 to altmax 
idxalt|-|array of altitude/flight levels ids
nalt|integer|total number of altitudes/flight levels
iprof|-|list of indices of points with an altitude profile

## WindSim methods
WindSim object can be accessed from the traffic module:
```python
from bluesky.traffic.windsim import WindSim
wind = WindSim()
```

Wind vector can be defined as a part of the wind field using method `add`:

```python
wind.add(52, 4, 0, 270, 50)
# the last three arguments can be repeated
wind.add(52, 4, 0, 270, 50, 1000, 300, 60)
```
## add arguments
|Name|Unit|Description
|----|------|-----------------
lat|deg|latitude
lon|deg|longitude
alt|ft|altitude
winddir|deg|wind direction 
windspd|kt|wind speed

Wind conditions can be retrieved using method `get`:
```
wind.get(52, 4, 0)
>>> WIND AT 52.00000, 4.00000: 270/50
```

## add arguments
|Name|Unit|Description
|----|------|-----------------
lat|deg|latitude
lon|deg|longitude
alt|ft|altitude



