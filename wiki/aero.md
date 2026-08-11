# Aerodynamic functions 
This module contains a set of standard aerodynamic functions, constants and conversions used in aeronautics. On this webpage, the functions and conversions are discussed and a few examples on their usage are given. On the bottom of this page, the constants used in Bluesky are listed.

***
## Functions 
The aerodynamic functions in this module include:

* Speed of sound \[m/s\] computation for a given altitude \[m\]. This is done by calling the function _vvsound()_
* Air density computation for a given altitude. This is done by calling the function _vdensity()_
* Pressure, air density and temperature computation for a given altitude. This is done by calling the function _vatmos()_

***

## Conversions
In addition, the module contains some useful speed conversion tools. 

* True airspeed \[m/s\] to Mach number \[-\] conversion (mach2tas)
* Equivalent airspeed \[m/s\] to true airspeed \[m/s\] (eas2tas)
* True airspeed \[m/s\] to equivalent airspeed \[m/s\] (tas2eas)
* Calibrated airspeed \[m/s\] to true airspeed \[m/s\] (cas2tas)
* True airspeed \[m/s\] to calibrated airspeed \[m/s\] (tas2cas)
* Mach number \[-\] to calibrated airspeed \[m/s\] (mach2cas)
* Calibrated airspeed \[m/s\] to mach number \[-\] (cas2mach)
* Meters \[m\] to feet \[ft\] (meters_to_feet_rounded)
* Metric speed \[m/s\] to knots \[kts\] (metric_spd_knots_rounded)

## Examples
```python
# Create arrays of altitude h and tas:
h = np.array([0, 1000, 2000])
tas = np.array([100,450,300])

# Compute aero results and print:
a = vvsound(h)
m = vtas2mach(tas,h)
p, rho, T  = vatmos(h)
print('vvsound:',a)
print('mach:',m)
print('p',p)
print('rho',rho)
print('T',T)
``` 
OUT:
```python
vvsound: [340.29398803 336.43397149 332.52915068]
mach: [0.29386355 1.33755815 0.90217654]
p [101324.99850086  89872.57620224  79491.64759792]
rho [1.225      1.11161793 1.0064451 ]
T [288.15 281.65 275.15]
```


***
## Constants 
The constants used in BlueSky consist of:

* kts = 0.514444              (m/s of 1 knot)
* ft  = 0.3048                (m of 1 foot)
* fpm = ft/60                 (feet per minute)
* inch = 0.0254               (m of 1 inch)
* sqft = 0.09290304           (m of 1 square feet)
* nm  = 1852.                 (m of 1 nautical mile)
* lbs = 0.453592              (kg of 1 pound mass)
* g0  = 9.80665               (m/s^2 Sea level gravity constant)
* R   = 287.05287             (Used in wikipedia table: checked with 11000 m)
* p0 = 101325.                (Pa Sea level pressure ISA)
* rho0 = 1.225                (kg/m^3  Sea level density ISA)
* T0   = 288.15               (K Sea level temperature ISA)
* Tstrat = 216.65             (K Stratosphere temperature (until alt= 22km))
* gamma = 1.40                (cp/cv: adiabatic index for air)
* gamma1 =  0.2               ((gamma-1)/2 for air)
* gamma2 = 3.5                (gamma/(gamma-1) for air)
* beta = -0.0065              (\[K/m\] ISA temp gradient below tropopause)
* Rearth = 6371000.           (m average earth radius)
* a0 = np x sqrt(gamma x R x T0)   (sea level speed of sound ISA)

