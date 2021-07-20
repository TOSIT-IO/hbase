# TDP HBase Notes

The version 2.6.1-0.0 of HBase is based on the `rel/2.6.1` tag of the Apache [repository](https://github.com/apache/hbase/tree/rel/2.6.1).

## Making a release

```
mvn clean package assembly:single -DskipTests -Dhadoop.profile=3.0
```

The command generates a `.tar.gz` file of the release at `./hbase-assembly/target/hbase-2.6.1-0.0-bin.tar.gz`.

Replace `package` for `install` in the above command to ensure built package is available in your local maven repository post build.

## Testing parameters

```
mvn test -Dhadoop.profile=3.0
```

- -Dhadoop.profile=3.0: Builds with Hadoop 3 (Hadoop TDP version is set with `hadoop-three.version`)
- --fail-never: Does not interrupt the tests if one module fails

## Test execution notes

See `./test_notes.txt`
