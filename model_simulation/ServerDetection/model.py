import random

import numpy as np

from ServerDetection.server import Server
from ServerDetection.node import Node
from ServerDetection.utils import set_s_n
from ServerDetection.log import log
from ServerDetection.child_servers import ComparisonServer, ScoreOnlyServer, AllCombinationServer, NRoundServer


class Model:
    def __init__(self, node_num, sybil_percent, malicious=0, server=1, error_rate=0.0):
        global normals
        self.server = self._init_server(server, node_num)
        self.counts = node_num
        self.nodes = {}
        self.normals = list(range(node_num))
        self.maps = Maps(10, 10)
        self.maps.init_loc(node_num)
        # self.maps.print_map()
        self.malicious: [] = []
        self.malicious_ap: {} = {}
        self.sybils = None
        self.error_rate = error_rate
        self.init_evils(sybil_percent, malicious)
        self.init_nodes()
        pass

    def init_evils(self, sybil_percent, malicious):
        self.sybils = random.sample(range(self.counts), int(sybil_percent * self.counts))
        for id in self.sybils:
            self.normals.remove(id)
        if malicious > 0:
            self.malicious = random.sample(self.sybils, malicious)
            self.malicious_ap = {}
            for id in self.malicious:
                if id in self.sybils:
                    self.sybils.remove(id)
                self.malicious_ap[id] = id + 30
        set_s_n(self.sybils, self.normals)

    def init_nodes(self):
        for id in range(self.counts):
            if id in self.normals:
                self.nodes[id] = Node(1, id, self.maps.get_node_loc(id))
            elif id in self.sybils:
                self.nodes[id] = Node(1, id, self.maps.get_node_loc(id), ap_loc=[0, 0], is_evil=True)
            else:
                self.nodes[id] = Node(1, id, self.maps.get_node_loc(id), ap_loc=self.maps.get_node_loc(id), is_evil=True)

    def main_process(self):
        # broadcasting and receiving process
        log("Sybils:", self.sybils)
        log("Normals:", self.normals)
        log("Malicious:", self.malicious)
        for rnd in range(self.server.total_rnds):
            self.server.begin_round()
            broadcasters = self.server.broadcast_node_list
            listeners = self.server.listen_node_list
            locations, signal_strength = self._init_broadcasters(broadcasters)
            for l in listeners:
                node: Node = self.nodes[l]
                if not node.is_evil:
                    self._normal_receiver_behavior(node, locations, signal_strength, broadcasters, self.server)
                else:
                    # Sybil receiver behavior
                    self._sybil_receiver_behavior(node, locations, signal_strength, broadcasters, self.server)
        self.server.process_finished()
        self.server.threads[0].join()
        log()

    def _init_broadcasters(self, broadcasters):
        locations = self._b_locations(broadcasters)
        signal_strength = self._b_signal_strength(broadcasters)
        return locations, signal_strength

    def _b_signal_strength(self, broadcasters):
        signal_strength = {}
        for id in broadcasters:
            node: Node = self.nodes[id]
            if node.is_evil:
                # Sybil specific behavior can be added, choose not to broadcast
                signal_strength[id] = node.get_b_s_s()
            else:
                signal_strength[id] = node.get_b_s_s()
        return signal_strength

    def _b_locations(self, broadcasters):
        locations = {}
        vacant_malicious = self._malicious_vacant(broadcasters)
        sybils = []
        normals = []
        for id in broadcasters:
            if self.nodes[id].is_evil:
                sybils.append(id)
            else:
                normals.append(id)
        self._normal_b_behavior(locations, normals)  # Later Sybil may use some other malicious devices to broadcast, loc changed
        if len(vacant_malicious) == 0:
            self._normal_b_behavior(locations, sybils)
        else:
            self._sybil_b_behavior(locations, sybils, vacant_malicious)
        return locations

    def _malicious_vacant(self, broadcasters):
        vacant_malicious = []
        for id in self.malicious:
            if id not in broadcasters:
                vacant_malicious.append(id)
        return vacant_malicious

    def _sybil_b_behavior(self, locations, sybils, vacant_malicious):
        if len(vacant_malicious) > len(sybils):
            for i in range(len(sybils)):
                m_node_id = vacant_malicious[i]
                loc_x_y = self.malicious_ap[m_node_id]
                locations[sybils[i]] = [loc_x_y, loc_x_y]
            return
        protected_sybils = random.sample(sybils, len(vacant_malicious))
        for id in protected_sybils:
            sybils.remove(id)
        for id in sybils:
            self._normal_b_behavior(locations, sybils)
        for i in range(len(vacant_malicious)):
            m_node_id = vacant_malicious[i]
            loc_x_y = self.malicious_ap[m_node_id]
            locations[protected_sybils[i]] = [loc_x_y, loc_x_y]

    def _normal_b_behavior(self, locations, lists):
        for id in lists:
            node: Node = self.nodes[id]
            if np.random.choice([0, 1], p=[self.error_rate, 1 - self.error_rate]) == 1:
                locations[id] = node.get_loc()
            else:
                locations[id] = [0, 0]

    def _normal_receiver_behavior(self, node, locations, signal_strength, broadcasters, server):
        for b in broadcasters:
            node.set_rssi(b, locations[b], signal_strength[b])
        node.report(server)

    def _sybil_receiver_behavior(self, node, locations, signal_strength, broadcasters, server):
        for b in broadcasters:
            if b in self.normals:
                node.set_rssi(b, [21, 21], 1)
            else:
                sybil: Node = self.nodes[b]
                node.set_rssi(b, sybil.gps_loc, 1)
        node.report(server)

    def _init_server(self, server, num):
        if server == 0:
            return Server(num)
        elif server == 1:
            return AllCombinationServer(num)
        elif server == 3:
            return ComparisonServer(num)
        elif server == 4:
            return ScoreOnlyServer(num)
        elif server == 5:
            return NRoundServer(num)


class NormalSybilModel(Model):
    def __init__(self, node_num, sybil_percent, malicious=0, server=1, error_rate=0.0, frame_ratio=0.0):
        super().__init__(node_num, sybil_percent, malicious, server, error_rate)
        self.frame_ratio = frame_ratio

    def _sybil_receiver_behavior(self, node, locations, signal_strength, broadcasters, server):
        if np.random.choice([0, 1], p=[self.frame_ratio, 1-self.frame_ratio]) == 1:
            self._normal_receiver_behavior(node, locations, signal_strength, broadcasters, server)
        else:
            super()._sybil_receiver_behavior(node, locations, signal_strength, broadcasters, server)


class DiffRoundModel(Model):
    def __init__(self, node_num, sybil_percent, malicious=0):
        super().__init__(node_num, sybil_percent, malicious)
        self.server_list = []
        self.server_list.append(self.server)
        self.server_list.append(NRoundServer(node_num))

    def main_process(self):
        # broadcasting and receiving process
        log("Sybils:", self.sybils)
        log("Normals:", self.normals)
        log("Malicious:", self.malicious)
        for server in self.server_list:
            for rnd in range(server.total_rnds):
                server.begin_round()
                broadcasters = server.broadcast_node_list
                listeners = server.listen_node_list
                locations, signal_strength = self._init_broadcasters(broadcasters)
                for l in listeners:
                    node: Node = self.nodes[l]
                    if not node.is_evil:
                        self._normal_receiver_behavior(node, locations, signal_strength, broadcasters, server)
                    else:
                        # Sybil receiver behavior
                        self._sybil_receiver_behavior(node, locations, signal_strength, broadcasters, server)
            server.process_finished()
            server.threads[0].join()
        log()


class Maps:
    def __init__(self, length: int, height: int):
        self.x_max = int(length)
        self.x_min = -int(length)
        self.y_max = int(height)
        self.y_min = -int(height)
        self.loc_map = {}

    def init_loc(self, node_num: int):
        for id in range(node_num):
            self.random_loc(id)

    def set_node(self, x, y, id):
        if x>self.x_max or x<self.x_min or y>self.y_max or y<self.y_min:
            return False
        if x==0 and y==0:
            return False
        for _, loc in self.loc_map.items():
            if x == loc[0] and y == loc[1]:
                return False
        self.loc_map[id] = [x, y]
        return True

    def get_node_loc(self, id):
        if id in self.loc_map:
            return self.loc_map[id]
        return None

    def random_loc(self, id):
        x, y = 0, 0
        while True:
            x = round(random.uniform(self.x_min, self.x_max), 5)
            y = round(random.uniform(self.y_min, self.y_max), 5)
            if self.set_node(x, y, id):
                break

    def print_map(self):
        log(self.loc_map)

