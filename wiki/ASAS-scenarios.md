Bluesky has a Airborne Separation Assurance System (ASAS) to which different conflict detection and conflict models can be added.

In order to enable ASAS, use the following command:

```
ASAS ON
```

There are, currently, three CD&R models which you may use:

+ MVP 
+ SSD
+ SWARM

Note that for SSD to be used, package [pyclipper](https://pypi.org/project/pyclipper/) must be installed

In order to enable one of these models, use:

```
RESO [MVP, SSD, SWARM]
```

SSD  has a visual component, where the velocities vectors which would lead to conflict are displayed. This may be used through the following command:

```
SSD [ALL, aicraftid]
```

The following may be used to test conflict resolution models:

```tct
# Cruise - cruise conflict --> close to airport
00:00:00.00>SYN SUPER 8

00:00:00.00>AREA 4.16, 4.16, -4.16, -4.16, 30000,10000

00:00:00.00>PAN 0. 0. 
00:00:00.00>ASAS ON
00:00:00.00>RESO SSD
00:00:00.00>TRAIL ON
00:00:00.00>SYMBOL

00:00:00.00>RMETHH BOTH

00:00:00.00>RMETHV OFF
```

Priority rules

```

```