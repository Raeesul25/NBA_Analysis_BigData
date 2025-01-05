package Q1;

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
import java.util.*;

public class MostScoringQuarterAnalysis {

    // Mapper Class
    public static class MostScoringQuarterMapper extends Mapper<LongWritable, Text, Text, Text> {

        @Override
        protected void map(LongWritable key, Text value, Context context) throws IOException, InterruptedException {

            Map<String, String> monthMapping = getMonthMapping();

            // Split the line into different fields.
            String[] values = value.toString().split(",");
            if (values.length < 25) return; // Skip invalid lines

            // extract the necessary fields
            String Score = values[24].trim();
            String GameID = values[2].trim();
            String Event = values[1].trim();
            String Period = values[5].trim();
            String Team = values[11].trim();

            // Fixing the invalid scores values like "02-Feb"
            if (Score.matches("\\d{1,2}-[a-zA-Z]{3}")) {
                String[] parts = Score.split("-");
                String day = parts[0];
                String month = monthMapping.getOrDefault(parts[1], "");
                if (!month.isEmpty()) {
                    Score = day + "-" + month;
                } else {
                    return; // Invalid score
                }
            } else if (Score.matches("[a-zA-Z]{3}-\\d{2}")) {
                String[] parts = Score.split("-");
                String month = monthMapping.getOrDefault(parts[0], "");
                String day = parts[1];
                if (!month.isEmpty()) {
                    Score = month + "-" + day;
                } else {
                    return; // Invalid score
                }
            }

            // Filter invalid records
            if (!Score.contains("-") || Team.isEmpty() || Score.isEmpty()) {
                return;
            }

            // Emit the GAME_ID as the key and the processed record as the value
            String record = String.join(",", Event, Period, Team, Score);
            context.write(new Text(GameID), new Text(record));
        }

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
    }

    // Reducer Class
    public static class MostScoringQuarterReducer extends Reducer<Text, Text, Text, Text> {

        private final Map<String, Map<Integer, Integer>> globalTeamPeriodScores = new HashMap<>();

        @Override
        protected void reduce(Text key, Iterable<Text> values, Context context) throws IOException, InterruptedException {
            // Key: gameID
            // Values: list of "eventNum, quarter, team, scoreboard"

            List<String[]> plays = new ArrayList<>();

            for (Text value : values) {
                String[] parts = value.toString().split(",");
                plays.add(parts);
            }

            // Sort the plays by eventNum
            plays.sort(Comparator.comparingInt(p -> Integer.parseInt(p[0])));

            Map<String, Map<Integer, Integer>> teamPeriodScores = new HashMap<>();

            String previousScore = null;

            for (String[] parts : plays) {
                int eventNum = Integer.parseInt(parts[0]);
                int period = Integer.parseInt(parts[1]);
                String scoringTeam = parts[2];
                String scoreboard = parts[3];

                int playScore;

                if (previousScore == null) {
                    playScore = Integer.parseInt(scoreboard.split("-")[1].trim());
                } else {
                    String[] currentScoreParts = scoreboard.split("-");
                    String[] previousScoreParts = previousScore.split("-");

                    int currentScore = Integer.parseInt(currentScoreParts[0].trim()) + Integer.parseInt(currentScoreParts[1].trim());
                    int previousScoreValue = Integer.parseInt(previousScoreParts[0].trim()) + Integer.parseInt(previousScoreParts[1].trim());

                    playScore = currentScore - previousScoreValue;
                }

                teamPeriodScores.putIfAbsent(scoringTeam, new HashMap<>());
                Map<Integer, Integer> quarterScores = teamPeriodScores.get(scoringTeam);
                quarterScores.put(period, quarterScores.getOrDefault(period, 0) + playScore);

                previousScore = scoreboard;
            }

            for (Map.Entry<String, Map<Integer, Integer>> teamEntry : teamPeriodScores.entrySet()) {
                String team = teamEntry.getKey();
                Map<Integer, Integer> quarterScores = teamEntry.getValue();

                globalTeamPeriodScores.putIfAbsent(team, new HashMap<>());
                Map<Integer, Integer> globalQuarterScores = globalTeamPeriodScores.get(team);

                for (Map.Entry<Integer, Integer> quarterEntry : quarterScores.entrySet()) {
                    int quarter = quarterEntry.getKey();
                    int score = quarterEntry.getValue();
                    globalQuarterScores.put(quarter, globalQuarterScores.getOrDefault(quarter, 0) + score);
                }
            }
        }

        @Override
        protected void cleanup(Context context) throws IOException, InterruptedException {
            for (Map.Entry<String, Map<Integer, Integer>> teamEntry : globalTeamPeriodScores.entrySet()) {
                String team = teamEntry.getKey();
                Map<Integer, Integer> quarterScores = teamEntry.getValue();

                int mostScoringPeriod = -1;
                int mostScore = -1;

                for (Map.Entry<Integer, Integer> quarterEntry : quarterScores.entrySet()) {
                    int quarter = quarterEntry.getKey();
                    int score = quarterEntry.getValue();

                    if (score > mostScore) {
                        mostScore = score;
                        mostScoringPeriod = quarter;
                    }
                }

                context.write(new Text(team), new Text("Most Scoring Period: " + mostScoringPeriod + ", Score: " + mostScore));
            }
        }
    }

    // Driver Method
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: MostScoringQuarterAnalysis <input path> <output path>");
            System.exit(-1);
        }

        Configuration conf = new Configuration();
        Job job = Job.getInstance(conf, "Most Scoring Quarter Analysis");
        job.setJarByClass(MostScoringQuarterAnalysis.class);

        job.setMapperClass(MostScoringQuarterMapper.class);
        job.setReducerClass(MostScoringQuarterReducer.class);

        job.setMapOutputKeyClass(Text.class);
        job.setMapOutputValueClass(Text.class);
        job.setOutputKeyClass(Text.class);
        job.setOutputValueClass(Text.class);

        FileInputFormat.addInputPath(job, new Path(args[0]));
        FileOutputFormat.setOutputPath(job, new Path(args[1]));

        System.exit(job.waitForCompletion(true) ? 0 : 1);
    }
}
