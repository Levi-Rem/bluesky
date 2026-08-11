# Entering Lat-Lon Coordinates

When entering commands in the command window or in scenario files, providing coordinates may be required. Lateral and longitudinal values are given in degrees. This can be done in different formats:

### Decimal notation
Coordinates may be given in decimal notation. Compass direction letters may be provided to coordinates, but are not required. If they are not provided, the lat-lon coordinates will be interpreted to be in north-eastern direction. The location of Delft University of Technology is expressed as:
51.990632, 4.375598 or N51.990632, E4.375598

### Degrees-Minutes-Seconds
The notation of degrees-minutes-seconds is supported in Bluesky. The same location can therefore also be expressed as 51'59'26.2746, 4'22'32.1528.

Please note that compass direction letters can also be used in this notation.

### Object callsign
If text is supplied as lat-lon coordinates, this is interpreted as the callsign of an object. The location of this object will be used as coordinates. Possible objects are aircraft, airports, waypoints and runways.

### Clicking the screen
If the user clicks the Bluesky screen with the mouse when asked for a location, the coordinates of the mouse are used as input. If the user clicks a specific object (aircraft/ waypoint/ airport), the objects coordinates are used.

[[Back to command reference.|Command Reference]]