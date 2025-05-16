<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->

# TDP HBase Notes

The version 2.6.2-1.0 of HBase is based on the `2.6.2` tag of the Apache [repository](https://github.com/apache/hbase/tree/rel/2.6.2).

## Jenkinfile

The file `./Jenkinsfile-sample` can be used in a Jenkins / Kubernetes environment to build and execute the unit tests of the Spark project. See []() for details on the environment.

## Making a release

```
mvn clean package assembly:single -DskipTests -Dhadoop.profile=3.0
```

The command generates `.tar.gz` files:
- `./hbase-assembly/target/hbase-2.6.2-1.0-bin.tar.gz`
- `./hbase-assembly/target/hbase-2.6.2-1.0-client-bin.tar.gz`


Replace `package` for `install` in the above command to ensure built package is available in your local maven repository post build.

## Testing parameters

```
mvn test -Dhadoop.profile=3.0
```

- -Dhadoop.profile=3.0: Builds with Hadoop 3 (Hadoop TDP version is set with `hadoop-three.version`)
- --fail-never: Does not interrupt the tests if one module fails

## Test execution notes

See `./test_notes.txt`
