#
# Copyright 2015-2016 WIND,FORTH
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#
import json
import sys

from datetime import datetime
from time import mktime

"""
Stats publisher module

It transform the results of stats module (i.e. hourly presences computation given a spatial region) into
json files compatible with weblyzard API

Usage: stats_publisher.py <spatial_division> <region> <timeframe>


--spatial division: csv file with the format GSM tower id --> spatial region
--region,timeframe: file name desired for the stored results. E.g. Roma 11-2015

Note: this sample is only valid for spatial regions <torri_roma.csv>

example: pyspark stats_publisher.py  ../spatial_regions/torri_roma.csv roma 06-2015

Results are stored into file: timeseries-<region>-<timeframe>-<spatial_division>.csv
"""

spatial_division = sys.argv[1]
region = sys.argv[2]
timeframe = sys.argv[3]

from_date_format = '%Y-%m-%d'
id_to_name = lambda id: id.replace("_", " ")
to_timestamp = lambda d: int(mktime(d.timetuple()))

with open('spatial_regions/roma_gsm.csv') as geo:
    coord = {x.split(";")[0][:5]: (x.split(";")[1:]) for x in geo.readlines()}

suffix = spatial_division.split("/")[-1]
with open('timeseries%s-%s-%s' %
          (region, timeframe, suffix)) as peaks:
    obs = []

    for i, p in enumerate(peaks.readlines()):
        s = p.split(",")
        target_location = s[0]
        loc = coord[s[0][:-2]]
        value = s[-1]
        date = datetime.strptime(s[1], from_date_format)
        hour = int(s[2])
        d = {}
        d["value"] = str(value).strip("\n")
        d["date"] = to_timestamp(date.replace(hour=hour))
        d["added_date"] = to_timestamp(datetime.now())
        d["target_location"] = [{"name": target_location, "point": {
            "lat": loc[0], "lon":loc[1].strip("\n")}}]
        d["indicator_id"] = "AreaPresence"
        d["_id"] = "%s_%s" % (d["indicator_id"], i)
        d["indicator_name"] = id_to_name(d["indicator_id"])
        d["uri"] = 'http://example.com/%s/%s' % (d["indicator_id"], d["_id"])
        obs.append(d)

with open("wl_timeseries%s-%s-%sarea_presence.json" %
          (region, timeframe, spatial_division.split("/")[-1]), "w") as file:
    json.dump(obs, file)
