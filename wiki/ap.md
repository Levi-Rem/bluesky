# Autopilot Logic
Bluesky contains a simulation of autopilot behaviour, which calculates the necessary state of aircraft at each time step so that they follow their pre-defined route.

The Autopilot class is a subclass of the [dynamic array](dynamicarray), which means that properties are stored for each aircraft. Indeed, the autopilot contains the following parameters arrays:

Name|Data type|Description
----|-----|-----------
trk|float| Desired track \[deg\]
spd|float| Desired ground speed \[m/s\]
tas|float| Desired TAS \[m/s\]
alt|float| Desired altitude \[m\]
vs|float| Desired vertical speed \[m/s\]
----|-----|-----------
dist2vs|float| Distance to top of descent \[m\]
swvnavvs|bool| Switch whether to use a given vertical speed or not \[-\]
vnavvs|float| Vertical speed used by VNAV \[m/s\]
----|-----|-----------
orig|string|Four letter code of origin airport \[-\]
dest|string|Four letter code of destination airport \[-\]
----|-----|-----------
route|[Route](route) object| contains a number of [waypoints](waypoint) for navigation

## Autopilot Logic
The autopilot makes the aircraft fly toward the intended destination. 

**Route following**

The route of an aircraft can be defined through waypoints. The command ADDWPT can be used as follow:

   ADDWPT acid,wpt/txt,[alt,spd,wpinroute,wpinroute]

Example of a complete route:

    CRE,AC0001,MAVIC,-0.07996323,0.09993262,329.69537250,100,30.00000000
    ORIG,AC0001,-0.07996323,0.09993262
    DEST,AC0001,0.00776849,0.04865687
    ADDWPT,AC0001,-0.05364372,0.08454988,100,29.96929608
    ADDWPT,AC0001,-0.02825476,0.06971103,100,29.96929608
    ADDWPT,AC0001,0.00776849,0.04865687,0.0,30.00000000
    LNAV,AC0001,ON
    VNAV,AC0001,ON

In the previous example, three waypoints were added to the fight path of the aircraft. The aircraft will start at the creation point (CRE), and will follow each waypoint in order. 

At any moment, each aircraft has an active waypoint. (If there are no waypoints, either because no waypoints were defined, or because the aircraft has passed the last waypoint, the aircraft will simply continue forward with its current state). At each time step, AutoPilot verifies whether the active waypoint has been reached. When the next waypoint requires a change in direction, the aircraft will need to turn. This can be done by:

- Fly by: aircraft perform a turn where the next waypoint is at the center of the turn. Aircraft initiates the turn at a distance from the waypoint (turn distance) according to the pre-defined turn radius (depending on the bank angle). By default waypoints are flyby.
- Fly over: aircraft initiate their turn exactly when they reach the waypoint (turn distance =0.0) 
- Fly turn: a flyby where the user may specify turn radius and optionally turn speed (mostly for drones)

To detect whether the waypoint has been reached, Bluesky calculates whether the turning distance has been reached. Once the turning distance has been reached, aircraft initiate a turn towards changing to the heading necessary to reach the next waypoint. The procedure is as follows:

For all aircraft i:
1. Bluesky checks which if aircraft i has reached their active waypoint
2. If it has (meaning it has reached its turning distance to theactive waypoint), aircraft i initiates a turn
3. The active waypoint of aircraft i is switched to the next waypoint in the route
4  VNAV profile is recomputed for the new leg for aircraft i

**Lateral/Vertical following**

The user can activate or deactivate lateral navigation with the use of the [LNAV] command. Vertical navigation can be enabled or disabled with the [VNAV] command. If LNAV is turned off, VNAV is automatically turned off as well.

If the current waypoint is above the aircraft (and VNAV is on), the aircraft will start climbing to the desired altitude. If the current waypoint is below the aircraft, the aircraft will descend as late as possible, limited by the given [VS] and groundspeed.

**Descent and Climb**

At each time step, the AutoPilot will check whether the aircraft may start ascent or descent. Currently, the Bluesky logic is as follows:

- Ignore and look beyond waypoints without an altitude constraint
- Climb as soon as possible after previous altitude constraints and climb as fast as possible, so arriving at alt earlier is ok
- Descend at the latest when necessary for the next altitude constraint which can be many waypoints beyond the current actual waypoint

**Setting an Aircraft's new State**

When an aircraft is given a [HDG],[ALT] or [SPD] command, the aircraft will automatically turn navigation off and follow the new directions.

