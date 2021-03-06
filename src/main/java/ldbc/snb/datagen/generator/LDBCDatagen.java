/*
 * Copyright (c) 2013 LDBC
 * Linked Data Benchmark Council (http://ldbc.eu)
 *
 * This file is part of ldbc_socialnet_dbgen.
 *
 * ldbc_socialnet_dbgen is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * ldbc_socialnet_dbgen is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with ldbc_socialnet_dbgen.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2011 OpenLink Software <bdsmt@openlinksw.com>
 * All Rights Reserved.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation;  only Version 2 of the License dated
 * June 1991.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package ldbc.snb.datagen.generator;

import ldbc.snb.datagen.dictionary.Dictionaries;
import ldbc.snb.datagen.hadoop.*;
import ldbc.snb.datagen.util.ConfigParser;
import ldbc.snb.datagen.vocabulary.SN;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Properties;

public class LDBCDatagen {

    static boolean initialized = false;
    public static synchronized void init (Configuration conf) {
        if(!initialized) {
            DatagenParams.readConf(conf);
            Dictionaries.loadDictionaries();
            SN.initialize();
            initialized = true;
        }
    }

    private void printProgress(String message) {
        System.out.println("************************************************");
        System.out.println("* " + message + " *");
        System.out.println("************************************************");
    }

    public int runGenerateJob(Configuration conf) throws Exception {

        String hadoopPrefix = conf.get("ldbc.snb.datagen.serializer.hadoopDir");
        FileSystem fs = FileSystem.get(conf);
        ArrayList<Float> percentages = new ArrayList<Float>();
        percentages.add(0.45f);
        percentages.add(0.45f);
        percentages.add(0.1f);


        long start = System.currentTimeMillis();
        printProgress("Starting: Person generation");
        long startPerson = System.currentTimeMillis();
        HadoopPersonGenerator personGenerator = new HadoopPersonGenerator( conf );
        personGenerator.run(hadoopPrefix+"/persons", "ldbc.snb.datagen.hadoop.UniversityKeySetter");
        long endPerson = System.currentTimeMillis();

        printProgress("Creating university location correlated edges");
        long startUniversity = System.currentTimeMillis();
        HadoopKnowsGenerator knowsGenerator = new HadoopKnowsGenerator(conf,
                                                                        "ldbc.snb.datagen.hadoop.UniversityKeySetter",
                                                                        "ldbc.snb.datagen.hadoop.RandomKeySetter",
                                                                        percentages,
                                                                        0,
                                                                        conf.get("ldbc.snb.datagen.generator.knowsGenerator"));

        knowsGenerator.run(hadoopPrefix+"/persons",hadoopPrefix+"/universityEdges");
        long endUniversity = System.currentTimeMillis();


        printProgress("Creating main interest correlated edges");
        long startInterest= System.currentTimeMillis();

        knowsGenerator = new HadoopKnowsGenerator(  conf,
                                                    "ldbc.snb.datagen.hadoop.InterestKeySetter",
                                                    "ldbc.snb.datagen.hadoop.RandomKeySetter",
                                                    percentages,
                                                    1,
                                                    conf.get("ldbc.snb.datagen.generator.knowsGenerator"));

        knowsGenerator.run(hadoopPrefix+"/persons",hadoopPrefix+"/interestEdges");
        long endInterest = System.currentTimeMillis();



        printProgress("Creating random correlated edges");
        long startRandom= System.currentTimeMillis();

        knowsGenerator = new HadoopKnowsGenerator(  conf,
                                                    "ldbc.snb.datagen.hadoop.RandomKeySetter",
                                                    "ldbc.snb.datagen.hadoop.RandomKeySetter",
                                                    percentages,
                                                    2,
                                                    "ldbc.snb.datagen.generator.RandomKnowsGenerator");

        knowsGenerator.run(hadoopPrefix+"/persons",hadoopPrefix+"/randomEdges");
        long endRandom= System.currentTimeMillis();




        fs.delete(new Path(DatagenParams.hadoopDir + "/persons"), true);
        printProgress("Merging the different edge files");
        ArrayList<String> edgeFileNames = new ArrayList<String>();
        edgeFileNames.add(hadoopPrefix+"/universityEdges");
        edgeFileNames.add(hadoopPrefix+"/interestEdges");
        edgeFileNames.add(hadoopPrefix+"/randomEdges");
        long startMerge = System.currentTimeMillis();
        HadoopMergeFriendshipFiles merger = new HadoopMergeFriendshipFiles(conf,"ldbc.snb.datagen.hadoop.RandomKeySetter");
        merger.run(hadoopPrefix+"/mergedPersons", edgeFileNames);
        long endMerge = System.currentTimeMillis();
        /*printProgress("Creating edges to fill the degree gap");
        long startGap = System.currentTimeMillis();
        knowsGenerator = new HadoopKnowsGenerator(conf,null, "ldbc.snb.datagen.hadoop.DegreeGapKeySetter", 1.0f);
        knowsGenerator.run(personsFileName2,personsFileName1);
        fs.delete(new Path(personsFileName2), true);
        long endGap = System.currentTimeMillis();
        */

        printProgress("Serializing persons");
        long startPersonSerializing= System.currentTimeMillis();
        HadoopPersonSerializer serializer = new HadoopPersonSerializer(conf);
        serializer.run(hadoopPrefix+"/mergedPersons");
        long endPersonSerializing= System.currentTimeMillis();

        long startPersonActivity= System.currentTimeMillis();
        if(conf.getBoolean("ldbc.snb.datagen.generator.activity", true)) {
            printProgress("Generating and serializing person activity");
            HadoopPersonActivityGenerator activityGenerator = new HadoopPersonActivityGenerator(conf);
            activityGenerator.run(hadoopPrefix+"/mergedPersons");

            int numThreads = DatagenParams.numThreads;
            int blockSize = DatagenParams.blockSize;
            int numBlocks = (int)Math.ceil(DatagenParams.numPersons / (double)blockSize);

            for( int i = 0; i < numThreads; ++i ) {
                if( i < numBlocks ) {
                    fs.copyToLocalFile(false, new Path(DatagenParams.hadoopDir + "/m" + i + "factors.txt"), new Path("./"));
                    fs.copyToLocalFile(false, new Path(DatagenParams.hadoopDir + "/m0friendList" + i + ".csv"), new Path("./"));
                }
            }
        }
        long endPersonActivity= System.currentTimeMillis();

        long startSortingUpdateStreams= System.currentTimeMillis();

        if(conf.getBoolean("ldbc.snb.datagen.serializer.updateStreams", false)) {

            printProgress("Sorting update streams ");

            int blockSize = DatagenParams.blockSize;
            int numBlocks = (int)Math.ceil(DatagenParams.numPersons / (double)blockSize);

            for( int i = 0; i < DatagenParams.numThreads; ++i) {
                int numPartitions = conf.getInt("ldbc.snb.datagen.serializer.numUpdatePartitions", 1);
                if( i < numBlocks ) {
                    for (int j = 0; j < numPartitions; ++j) {
                        HadoopFileSorter updateStreamSorter = new HadoopFileSorter(conf, LongWritable.class, Text.class);
                        HadoopUpdateStreamSerializer updateSerializer = new HadoopUpdateStreamSerializer(conf);
                        updateStreamSorter.run(DatagenParams.hadoopDir + "/temp_updateStream_person_" + i + "_" + j, DatagenParams.hadoopDir + "/updateStream_person_" + i + "_" + j);
                        fs.delete(new Path(DatagenParams.hadoopDir + "/temp_updateStream_person_" + i + "_" + j), true);
                        updateSerializer.run(DatagenParams.hadoopDir + "/updateStream_person_" + i + "_" + j, i, j, "person");
                        fs.delete(new Path(DatagenParams.hadoopDir + "/updateStream_person_" + i + "_" + j), true);
                        if( conf.getBoolean("ldbc.snb.datagen.generator.activity", false)) {
                            updateStreamSorter.run(DatagenParams.hadoopDir + "/temp_updateStream_forum_" + i + "_" + j, DatagenParams.hadoopDir + "/updateStream_forum_" + i + "_" + j);
                            fs.delete(new Path(DatagenParams.hadoopDir + "/temp_updateStream_forum_" + i + "_" + j), true);
                            updateSerializer.run(DatagenParams.hadoopDir + "/updateStream_forum_" + i + "_" + j, i, j, "forum");
                            fs.delete(new Path(DatagenParams.hadoopDir + "/updateStream_forum_" + i + "_" + j), true);
                        }
                    }
                } else {
                    for (int j = 0; j < numPartitions; ++j) {
                        fs.delete(new Path(DatagenParams.hadoopDir + "/temp_updateStream_person_" + i + "_" + j), true);
                        fs.delete(new Path(DatagenParams.hadoopDir + "/temp_updateStream_forum_" + i + "_" + j), true);
                    }
                }
            }

            long minDate = Long.MAX_VALUE;
            long maxDate = Long.MIN_VALUE;
            long count = 0;
            for( int i = 0; i < DatagenParams.numThreads; ++i) {
                Path propertiesFile = new Path(DatagenParams.hadoopDir+"/temp_updateStream_person_"+i+".properties");
                FSDataInputStream file = fs.open(propertiesFile);
                Properties properties = new Properties();
                properties.load(file);
                long aux;
                aux = Long.parseLong(properties.getProperty("ldbc.snb.interactive.min_write_event_start_time"));
                minDate = aux < minDate ? aux : minDate;
                aux = Long.parseLong(properties.getProperty("ldbc.snb.interactive.max_write_event_start_time"));
                maxDate = aux > maxDate ? aux : maxDate;
                aux = Long.parseLong(properties.getProperty("ldbc.snb.interactive.num_events"));
                count += aux;
                file.close();
                fs.delete(propertiesFile,true);

                if( conf.getBoolean("ldbc.snb.datagen.generator.activity", false)) {
                    propertiesFile = new Path(DatagenParams.hadoopDir + "/temp_updateStream_forum_" + i + ".properties");
                    file = fs.open(propertiesFile);
                    properties = new Properties();
                    properties.load(file);
                    aux = Long.parseLong(properties.getProperty("ldbc.snb.interactive.min_write_event_start_time"));
                    minDate = aux < minDate ? aux : minDate;
                    aux = Long.parseLong(properties.getProperty("ldbc.snb.interactive.max_write_event_start_time"));
                    maxDate = aux > maxDate ? aux : maxDate;
                    aux = Long.parseLong(properties.getProperty("ldbc.snb.interactive.num_events"));
                    count += aux;
                    file.close();
                    fs.delete(propertiesFile, true);
                }
            }

            OutputStream output = fs.create(new Path(DatagenParams.socialNetworkDir+"/updateStream"+".properties"),true);
            output.write(new String("ldbc.snb.interactive.gct_delta_duration:" + DatagenParams.deltaTime + "\n").getBytes());
            output.write(new String("ldbc.snb.interactive.min_write_event_start_time:" + minDate + "\n").getBytes());
            output.write(new String("ldbc.snb.interactive.max_write_event_start_time:" + maxDate + "\n").getBytes());
            output.write(new String("ldbc.snb.interactive.update_interleave:" + (maxDate - minDate) / count + "\n").getBytes());
            output.write(new String("ldbc.snb.interactive.num_events:" + count).getBytes());
            output.close();
        }

        long endSortingUpdateStreams= System.currentTimeMillis();

        printProgress("Serializing invariant schema ");
        long startInvariantSerializing= System.currentTimeMillis();
        HadoopInvariantSerializer invariantSerializer = new HadoopInvariantSerializer(conf);
        invariantSerializer.run();
        long endInvariantSerializing= System.currentTimeMillis();



        long end = System.currentTimeMillis();



        System.out.println(((end - start) / 1000)
                + " total seconds");
        System.out.println("Person generation time: "+((endPerson - startPerson) / 1000));
        System.out.println("University correlated edge generation time: "+((endUniversity - startUniversity) / 1000));
        System.out.println("Interest correlated edge generation time: "+((endInterest - startInterest) / 1000));
        System.out.println("Random correlated edge generation time: "+((endRandom - startRandom) / 1000));
        System.out.println("Edges merge time: "+((endMerge - startMerge) / 1000));
        System.out.println("Person serialization time: "+((endPersonSerializing - startPersonSerializing) / 1000));
        System.out.println("Person activity generation and serialization time: "+((endPersonActivity - startPersonActivity) / 1000));
        System.out.println("Sorting update streams time: "+((endSortingUpdateStreams - startSortingUpdateStreams) / 1000));
        System.out.println("Invariant schema serialization time: "+((endInvariantSerializing - startInvariantSerializing) / 1000));
        System.out.println("Total Execution time: "+((end - start) / 1000));
        return 0;
    }

    public static void main(String[] args) /*throws Exception*/ {

        try {
        Configuration conf = ConfigParser.initialize();
        ConfigParser.readConfig(conf, args[0]);
        ConfigParser.readConfig(conf, LDBCDatagen.class.getResourceAsStream("/params.ini"));
        conf.set("ldbc.snb.datagen.serializer.hadoopDir",conf.get("ldbc.snb.datagen.serializer.outputDir")+"/hadoop");
        conf.set("ldbc.snb.datagen.serializer.socialNetworkDir",conf.get("ldbc.snb.datagen.serializer.outputDir")+"/social_network");
        ConfigParser.printConfig(conf);
//        conf.setBoolean("mapreduce.map.output.compress", true);
//       conf.setBoolean("mapreduce.output.fileoutputformat.compress", false);

        // Deleting existing files
        FileSystem dfs = FileSystem.get(conf);
        dfs.delete(new Path(conf.get("ldbc.snb.datagen.serializer.hadoopDir")), true);
        dfs.delete(new Path(conf.get("ldbc.snb.datagen.serializer.socialNetworkDir")), true);

        // Create input text file in HDFS
        LDBCDatagen datagen = new LDBCDatagen();
        LDBCDatagen.init(conf);
            datagen.runGenerateJob(conf);
        }catch(Exception e ) {
            System.err.println("Error during execution");
            System.err.println(e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
