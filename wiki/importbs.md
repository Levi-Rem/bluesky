# Importing BlueSky as a python package
It is possible to use BlueSky as a Python library (Look at [[Installation]] for instructions on how to install BlueSky from PyPI using pip). In principle, any BlueSky running mode (sim, server, client) can be initialised like this, but most likely you will only want to import BlueSky's sim logic. To import BlueSky, and configure it for simulation mode, use the following:

```python
    import bluesky as bs
    bs.init(mode='sim', detached=True)
```

This configures BlueSky for a simulation process, without networking (if you do want networking, for instance to connect your custom simulation process to a server+gui, just leave out the `detached=True`).

The default way to control the simulation is through stack commands. For example, to create an aircraft:
```python
    bs.stack.stack('CRE KL204 B744 EHAM/RW27')
```
These stack commands are only processed when the simulation is updated. To perform a single simulation step, do:
```python
    bs.sim.step()
```
Accessing simulation data can be done directly:
```python
    print('Latitudes:', bs.traf.lat)
```

A full example of using BlueSky in your own program can be found in [this jupyter notebook](../blob/master/docs/python_demo.ipynb).