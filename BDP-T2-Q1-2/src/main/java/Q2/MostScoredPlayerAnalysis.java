package Q2;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MostScoredPlayerAnalysis {

    // Mapper Class
    public static class HighestScoringPlayerMapper extends Mapper<LongWritable, Text, Text, Text> {

        // Month mapping method
        private Map<String, String> getMonthMapping() {
            Map<String, String> monthMapping = new HashMap<>();
            monthMapping.put("Jan", "1");
            monthMapping.put("Feb", "2");
            monthMapping.put("Mar", "3");
            monthMapping.put("Apr", "4");
            monthMapping.put("May", "5");
            monthMapping.put("Jun", "6");
            monthMapping.put("Jul", "7");
            monthMapping.put("Aug", "8");
            monthMapping.put("Sep", "9");
            monthMapping.put("Oct", "10");
            monthMapping.put("Nov", "11");
            monthMapping.put("Dec", "12");
            return monthMapping;
        }

        @Override
        protected void map(LongWritable key, Text value, Context context)
                throws IOException, InterruptedException {

            Map<String, String> monthMapping = getMonthMapping();

            String[] values = value.toString().split(",");
            if (values.length < 25) return;

            String score = values[24].trim();
            String gameId = values[2].trim();
            String player = values[7].trim();
            String event = values[1].trim();

            // Normalize score format
            if (score.matches("\\d{1,2}-[a-zA-Z]{3}")) {
                String[] parts = score.split("-");
                String day = parts[0];
                String month = monthMapping.getOrDefault(parts[1], "");
                if (!month.isEmpty()) {
                    score = day + "-" + month;
                } else {
                    return;
                }
            } else if (score.matches("[a-zA-Z]{3}-\\d{2}")) {
                String[] parts = score.split("-");
                String month = monthMapping.getOrDefault(parts[0], "");
                String day = parts[1];
                if (!month.isEmpty()) {
                    score = month + "-" + day;
                } else {
                    return;
                }
            }

            // Validate record
            if (!score.contains("-") || player.isEmpty() || score.isEmpty()) {
                return;
            }

            // Emit record
            String record = String.join(",", event, player, score);
            context.write(new Text(gameId), new Text(record));
        }
    }

    // Reducer Class
    public static class HighestScoringPlayerReducer extends Reducer<Text, Text, Text, Text> {
        // Global map to track player scores
        private final Map<String, Integer> playerScores = new HashMap<>();

        @Override
        protected void reduce(Text key, Iterable<Text> values, Context context)
                throws IOException, InterruptedException {

            // List to store and sort plays
            List<String[]> plays = new ArrayList<>();

            // Collect plays
            for (Text value : values) {
                String[] parts = value.toString().split(",");
                plays.add(parts);
            }

            // Sort plays by event number
            plays.sort(Comparator.comparingInt(p -> Integer.parseInt(p[0])));

            // Track previous score for calculation
            String previousScore = null;

            for (String[] parts : plays) {
                String player = parts[1];
                String currentScoreboard = parts[2];

                int playScore;

                // Calculate play score
                if (previousScore == null) {
                    playScore = Integer.parseInt(currentScoreboard.split("-")[0].trim()) +
                            Integer.parseInt(currentScoreboard.split("-")[1].trim());
                } else {
                    int currentScore = Integer.parseInt(currentScoreboard.split("-")[0].trim()) +
                            Integer.parseInt(currentScoreboard.split("-")[1].trim());
                    int previousScoreValue = Integer.parseInt(previousScore.split("-")[0].trim()) +
                            Integer.parseInt(previousScore.split("-")[1].trim());

                    playScore = currentScore - previousScoreValue;
                }

                // Update player score
                playerScores.put(player, playerScores.getOrDefault(player, 0) + playScore);
                previousScore = currentScoreboard;
            }
        }

        @Override
        protected void cleanup(Context context) throws IOException, InterruptedException {
            // Find the highest-scoring player
            String highestScoringPlayer = null;
            int highestScore = 0;

            for (Map.Entry<String, Integer> entry : playerScores.entrySet()) {
                String player = entry.getKey();
                int totalScore = entry.getValue();

                if (totalScore > highestScore) {
                    highestScore = totalScore;
                    highestScoringPlayer = player;
                }
            }

            // Emit the highest-scoring player
            if (highestScoringPlayer != null) {
                context.write(new Text("Most Scoring Player "),
                        new Text(highestScoringPlayer + ", Total Score: " + highestScore));
            }
        }
    }

    // Main method to configure and run the MapReduce job
    public static void main(String[] args) throws Exception {
        // Check input arguments
        if (args.length != 2) {
            System.err.println("Usage: HighestScoringPlayerAnalysis <input path> <output path>");
            System.exit(-1);
        }

        // Create configuration
        Configuration conf = new Configuration();

        // Create job instance
        Job job = Job.getInstance(conf, "Highest Scoring Player Analysis");
        job.setJarByClass(MostScoredPlayerAnalysis.class);

        // Set Mapper and Reducer
        job.setMapperClass(HighestScoringPlayerMapper.class);
        job.setReducerClass(HighestScoringPlayerReducer.class);

        // Set output key and value types
        job.setOutputKeyClass(Text.class);
        job.setOutputValueClass(Text.class);

        // Ensure single reducer for global comparison
        job.setNumReduceTasks(1);

        // Set input and output paths
        FileInputFormat.addInputPath(job, new Path(args[0]));
        FileOutputFormat.setOutputPath(job, new Path(args[1]));

        // Submit the job and wait for completion
        System.exit(job.waitForCompletion(true) ? 0 : 1);
    }
}