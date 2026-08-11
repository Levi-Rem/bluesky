# The simulation object
The simulation object keeps track of the simulation state, and manages its timing.

The simulation state corresponds to one of four enumerated values, which can be accessed as `bluesky.INIT`, `bluesky.OP`, `bluesky.HOLD`, and `bluesky.END`.

Name|Value|Description
----|-----|-----------
**init**|0| Simulation time is equal to zero, and is not yet progressing. This is the clean state of the simulator.
**op**  |1| The simulation is running, and time is progressing.
**hold**|2| The simulation is paused, simulation time is non-zero.
**end** |3| The simulation is ended, and BlueSky is quitting.

## Simulation members:

|Name|Unit|Description
|----|-----|-----------------
state|  -  |The current simulation state.
simt | sec |Simulation time
simdt| sec |Simulation timestep
dtmult| -  |Simulation timestep multiplier: run sim at n x speed
simtclock|sec|Simulated clock time
ffmode|bool|Flag indicating running at fixed rate or fast time