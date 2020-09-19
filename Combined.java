import java.io.*;
import java.util.HashSet;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.TimeUnit;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.*;
import org.apache.hadoop.io.*;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;

public class Combined {

    public static class IDFMapper
            extends Mapper<Object, Text, Text, IntWritable>{

        private final static IntWritable one = new IntWritable(1);
        private Text word = new Text();

        public void map(Object key, Text value, Context context
        ) throws IOException, InterruptedException {

            String line = new String(value.toString());
            String text = line.substring(line.indexOf(", \"text\":") + 9);
            StringTokenizer itr = new StringTokenizer(text.toLowerCase().replaceAll("\\\\[a-z]", " ").replaceAll("-", " "));
            String cur = "";
            HashSet used = new HashSet();

            while (itr.hasMoreTokens()) {
                cur = itr.nextToken().replaceAll("[\\\\0-9~`!@#$%^&*()\\-_+=\\,.<>?/'\":;{}\\[\\]\\|]", "");
                if (! cur.equals("")) {
                    if (!used.contains(cur)) {
                        used.add(cur);
                        word.set(cur);
                        context.write(word, one);
                    }
                }
            }
        }
    }

    public static class IDFReducer
            extends Reducer<Text,IntWritable,Text,IntWritable> {
        private IntWritable result = new IntWritable();

        public void reduce(Text key, Iterable<IntWritable> values,
                           Context context
        ) throws IOException, InterruptedException {
            int sum = 0;
            for (IntWritable val : values) {
                sum += val.get();
            }

            result.set(sum);
            context.write(key, result);
        }
    }

    public static class IndMapper
            extends Mapper<Object, Text, Text, MapWritable>{

        private final static IntWritable one = new IntWritable(1);
        private Text word = new Text();

        public void map(Object key, Text value, Context context
        ) throws IOException, InterruptedException {

            String line = value.toString(); // acquiring the doc
            String text = line.substring(line.indexOf(", \"text\":") + 9); // getting doc text
            String id = line.substring(8, line.indexOf("\", \"url\"")); // getting doc id

            StringTokenizer itr = new StringTokenizer(text.toLowerCase().replaceAll("\\\\[a-z]", " ").replaceAll("-", " ")); // iterating through text
            String cur = ""; // current word in text
            text = null;
            line = null;
            MapWritable map = new MapWritable(); // <word, # of its occurences in the text>

            // iterating through text
            while (itr.hasMoreTokens()) {
                cur = itr.nextToken().replaceAll("[\\\\0-9~`!@#$%^&*()\\-_+=\\,.<>?/'\":;{}\\[\\]\\|]", ""); //cur word
                if (! cur.equals("")) {
                    word = new Text(cur);
                    if (! map.containsKey(word)) {
                        map.put(word, one);
                    }
                    else {
                        IntWritable r = (IntWritable) map.get(word);
                        map.put(word, new IntWritable(r.get() + 1));
                    }
                }
            }

            context.write(new Text(id), map); // passing to reducer
        }
    }

    public static class IndReducer
            extends Reducer<Text, MapWritable, Text, MapWritable> {

        public void reduce(Text key, Iterable<MapWritable> values,
                           Context context
        ) throws IOException, InterruptedException {

            Configuration conf = context.getConfiguration(); // get config
            MapWritable result = new MapWritable(); // <hash of word, tf-idf of word>

            Integer length = 0; // # of words in doc text
            for (MapWritable map: values) {
                Set<Writable> keys = map.keySet(); // getting all words that occurred in text
                for(Writable k: keys){
                    String word = ((Text) k).toString();
                    Integer tf = ((IntWritable)(map.get(k))).get();

                    length = length + tf; // updating len

                    Integer idf = conf.getInt(word, -1);
                    Float tfidf = (float)tf / (float)idf;

                    result.put(new IntWritable(word.hashCode()), new FloatWritable(tfidf));
                }
            }

            context.write(new Text(key.toString() + " length: " + length.toString()), result); // writing the result
        }
    }


    public static void main(String[] args) throws Exception {

        ///////////// IDF /////////////
        Configuration conf = new Configuration();
        Job job = Job.getInstance(conf, "word count");
        job.setJarByClass(Combined.class);
        job.setMapperClass(IDFMapper.class);
        job.setCombinerClass(IDFReducer.class);
        job.setReducerClass(IDFReducer.class);
        job.setOutputKeyClass(Text.class);
        job.setOutputValueClass(IntWritable.class);
        FileInputFormat.setInputDirRecursive(job, true);
        FileInputFormat.addInputPath(job, new Path(args[0]));
        Path path = new Path("output_idf");
        FileOutputFormat.setOutputPath(job, path);
        job.waitForCompletion(true);
        ///////////// IDF /////////////


        ///////////// Word2Vec & TF-IDF /////////////
        // setting configs
        conf = new Configuration();

        ///////////// reading IDF from file /////////////

        FileSystem fs = FileSystem.get(conf);
        BufferedReader reader;
        try {

            // listing filenames in the dir
            FileStatus[] fileStatuses = fs.listStatus(new Path("output_idf"));

            // going through each file
            for(FileStatus status: fileStatuses) {
                String filename = status.getPath().toString();
                if (!filename.contains("SUCCESS")) {
                    // reading files
                    path = new Path("output_idf/" + filename.substring(filename.indexOf("output_idf/") + "output_idf/".length()));

                    reader = new BufferedReader(new InputStreamReader(fs.open(path)));

                    String line = reader.readLine();
                    while(line != null){
                        StringTokenizer itr = new StringTokenizer(line);
                        String cur_word = "";
                        Integer cur_idf = 0;
                        // iterating through line
                        if(itr.hasMoreTokens()){
                            cur_word = itr.nextToken();
                            if (itr.hasMoreTokens()) {
                                cur_idf = Integer.parseInt(itr.nextToken().replaceAll("[^0-9]", ""));
                                conf.setInt(cur_word, cur_idf); //passing <word, idf> to mapreduce
                            }
                        }

                        line = reader.readLine(); // reading the next line
                    }
                }
            }

            fileStatuses = null;

        } catch (IOException e){
            e.printStackTrace();
        }

        ///////////// reading IDF from file /////////////

        job = null;

        // initializing job
        Job job2 = Job.getInstance(conf, "Indexer");

        // setting corresponding classes
        job2.setJarByClass(Combined.class);
        job2.setMapperClass(IndMapper.class);
        job2.setReducerClass(IndReducer.class);
        job2.setOutputKeyClass(Text.class);
        job2.setOutputValueClass(MapWritable.class);

        // Files
        FileInputFormat.setInputDirRecursive(job2, true);
        FileInputFormat.addInputPath(job2, new Path(args[0]));
        FileOutputFormat.setOutputPath(job2, new Path(args[1]));

        // Starting the job
        job2.waitForCompletion(true);

        ///////////// Word2Vec & TF-IDF /////////////

        ///////////// AVG DOCs LEN /////////////
        int n_docs = 0; // # of documents
        int sum = 0; // sum of lens of all docs
        float avg = 0; // avg len of docs
        try {

            // listing filenames in the dir
            FileStatus[] fileStatuses = fs.listStatus(new Path(args[1]));

            // going through each file
            for(FileStatus status: fileStatuses) {
                String filename = status.getPath().toString();
                if (!filename.contains("SUCCESS")) {
                    // reading files
                    path = new Path(args[1] + "/" + filename.substring(filename.indexOf(args[1]) + args[1].length() + 1));

                    reader = new BufferedReader(new InputStreamReader(fs.open(path)));

                    String line = reader.readLine();
                    while(line != null){
                        if(line.length() > 0) {
                            n_docs ++; // incrementing # of docs
                            // getting len of the doc
                            sum += Integer.parseInt(line.substring(line.indexOf("length: ") + "length: ".length(), line.indexOf("{")).replaceAll("[^0-9]", ""));
                        }
                        line = reader.readLine(); // reading the next line
                    }
                }
            }
            // calculating the avg len
            avg = (float) sum / (float) n_docs;

            FSDataOutputStream out = fs.create(new Path(args[1] + "/avg_len"));
            out.writeChars(Float.toString(avg));

        } catch (IOException e){
            e.printStackTrace();
        }
        ///////////// AVG DOCs LEN  /////////////

        System.exit(1);

    }
}