import json
import unittest
from types import SimpleNamespace
from unittest.mock import Mock
from bluesky.plugins.training_adapter.engine import BlueSkyEngine
from bluesky.tools.aero import ft


class NativeEffectsTest(unittest.TestCase):
    def setUp(self):
        self.engine=BlueSkyEngine('.')
        self.route=SimpleNamespace(nwp=2,iactwp=0,wpname=['P1','P2'],wplat=[0.,1.],wplon=[0.,1.],wpalt=[-999.,-999.],wprta=[-999.,-999.],calcfp=Mock(),direct=Mock())
        self.traffic=SimpleNamespace(id=['TEST1'],id2idx=lambda name:0 if name=='TEST1' else -1,
            ap=SimpleNamespace(route=[self.route],selhdgcmd=Mock(),selaltcmd=Mock(),selspdcmd=Mock()),
            lat=[-1.],lon=[-1.],hdg=[45.],alt=[8000*ft],cas=[120.],gs=[130.],swlnav=[True],swvnav=[False],swvnavspd=[False])
        self.engine._bs=SimpleNamespace(traf=self.traffic,sim=SimpleNamespace(simt=100.))
        self.engine._require_initialized=lambda:None
        self.engine._validate_altitude_ceiling=lambda *_:None

    def apply(self,kind,parameters,key='command-1',channels=None):
        return self.engine.execute_instruction({'type':kind,'callsign':'TEST1','commandId':key,'parametersJson':json.dumps(parameters),'affectedChannels':channels or ['LATERAL']})

    def test_offset_mutates_geometry_and_clear_restores_exact_route(self):
        original=(list(self.route.wplat),list(self.route.wplon))
        self.apply('OFFSET',{'action':'ENTER','side':'R','distanceNm':2})
        self.assertNotEqual(original,(self.route.wplat,self.route.wplon))
        self.apply('OFFSET',{'action':'CLEAR'},'clear')
        self.assertEqual(original,(self.route.wplat,self.route.wplon))

    def test_level_and_time_constraints_reach_native_route(self):
        self.apply('P_LEVEL',{'legPoint':'P2','altitudeFtMsl':12000},channels=['BUSINESS_FIELD'])
        self.assertAlmostEqual(12000*ft,self.route.wpalt[1])
        self.apply('P_TIME',{'legPoint':'P2','targetTimeSeconds':600},'time',channels=['BUSINESS_FIELD'])
        self.assertEqual(600,self.route.wprta[1])
        self.assertTrue(self.traffic.swvnav[0])

    def test_cancel_cleans_matching_controller_and_preserves_newer_command(self):
        self.engine._active_instructions={'TEST1':{'LATERAL':'new'}}
        self.engine._orbit_executions={'TEST1':{'commandId':'new'}}
        self.engine.cancel_instruction('TEST1','old',['LATERAL'])
        self.assertIn('TEST1',self.engine._orbit_executions)
        self.engine.cancel_instruction('TEST1','new',['LATERAL'])
        self.assertNotIn('TEST1',self.engine._orbit_executions)
        self.assertFalse(self.traffic.swlnav[0])

    def test_reset_removes_all_persistent_controllers(self):
        self.engine._orbit_executions['TEST1']={'commandId':'old'}
        self.engine._offset_routes['TEST1']=([1],[2])
        self.engine._clear_controllers()
        self.assertFalse(self.engine._orbit_executions)
        self.assertFalse(self.engine._offset_routes)

    def test_auto_resume_rejects_all_points_behind_aircraft(self):
        self.traffic.hdg[0]=225.
        with self.assertRaisesRegex(ValueError,'未来航段'):
            self.engine._forward_route_point(0)
        self.traffic.hdg[0]=45.
        self.assertEqual('P1',self.engine._forward_route_point(0))

    def test_overflying_threshold_cannot_emit_landed_until_touchdown(self):
        self.traffic.lat[0]=-0.001;self.traffic.lon[0]=0
        approach={'commandId':'ils-1','airportLat':0.,'airportLon':0.,'finalHdg':0.,'fafLat':-0.1,'fafLon':0.,'landed':False,'lateralCaptured':True,'verticalCaptured':True,'fafPassed':True,'apWp':'P1','fafWp':'P1','elevationFt':50}
        self.engine._ils_approaches['TEST1']=approach
        self.engine._update_ils_approaches()
        self.assertFalse(approach['landed'])
        self.assertFalse(self.engine._flight_events.get('TEST1'))
        self.traffic.alt[0]=50*ft
        self.engine._update_ils_approaches()
        self.assertTrue(approach['landed'])
        self.assertEqual('LANDED',self.engine._flight_events['TEST1'][0]['type'])
        self.traffic.ap.selaltcmd.assert_called_with(0,50*ft)
        self.traffic.ap.selspdcmd.assert_called_with(0,0.0)

    def test_intercept_inside_faf_continues_towards_threshold(self):
        self.traffic.lat[0]=-0.1;self.traffic.lon[0]=0
        approach={'commandId':'ils-2','airportLat':0.,'airportLon':0.,'finalHdg':0.,'fafLat':-0.2,'fafLon':0.,'landed':False,'lateralCaptured':False,'verticalCaptured':False,'apWp':'P2','fafWp':'P1','elevationFt':50}
        self.engine._ils_approaches['TEST1']=approach
        self.engine._update_ils_approaches()
        self.assertTrue(approach['fafPassed'])
        self.traffic.ap.route[0].direct.assert_called_with(0,'P2')
