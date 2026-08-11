The OpenAP performance model allows for using different types of aircraft and obtaining the performance results. For each aircraft type, parameters such as dimensions, limits, nominal cruise condition, and engine options are given.

OpenAP is one of the performance models available in Bluesky.

For more information refer to [OpenAP: The open-source aircraft performance model and associated toolkit](https://www.researchgate.net/publication/332013573_OpenAP_The_open-source_aircraft_performance_model_and_associated_toolkit)

# Enable OpenAP

In settings.cfg select the following option:
```python
# Select the performance model. options: 'openap', 'bada', 'legacy'
performance_model = 'openap'
```

# Databases

+ Aircraft
+ Engines
+ Drag polar
+ Kinematic (based on the results of a data-driven statistical model ([Wrap](https://github.com/junzis/wrap))
+ Navigation

# Available Aircraft Types

Types of fix wing aircraft are defined in:
[Aicraft Types](https://github.com/TUDelft-CNS-ATM/bluesky/blob/master/bluesky/resources/performance/OpenAP/fixwing/aircraft.json)

For rotors aircraft, the types are defined in:
[Rotor Types](https://github.com/TUDelft-CNS-ATM/bluesky/blob/master/bluesky/resources/performance/OpenAP/rotor/aircraft.json)

The name of each aircraft object identifies the name which should be used to create each aircraft type.


# Calculations
+ **thrust**: aircraft net thrust calculated based on drag
+ **drag**: aircraft drag calculation based on OpenAP drag polar
+ **fuel**: fuel flow calcutation
+ **limtis**: flight envelop based on WRAP model
+ **phase**: flight phase calculation based on altitude, speed, vertical rate

