package org.mitre.synthea.modules;

import static org.mitre.synthea.world.concepts.BMI.calculate;

import java.util.Map;

import org.mitre.synthea.engine.Module;
import org.mitre.synthea.helpers.Utilities;
import org.mitre.synthea.world.agents.Person;
import org.mitre.synthea.world.concepts.BMI;
import org.mitre.synthea.world.concepts.BiometricsConfig;
import org.mitre.synthea.world.concepts.GrowthChart;
import org.mitre.synthea.world.concepts.PediatricGrowthTrajectory;
import org.mitre.synthea.world.concepts.VitalSign;

/**
 * This module allows patients in Synthea to lose weight. It will be triggered when patients
 * hit a specified age and weight threshold. At that point, patients may chose to manage
 * their weight. If they do, it will suspend any weight adjustments from the LifecycleModule.
 * Patients will then lose weight based on adherence generated by this module. Given successful
 * weight loss, the patients have the chance to later regain all of their weight (very likely
 * given the default probabilities).
 */
public final class WeightLossModule extends Module {

  public WeightLossModule() {
    this.name = "Weight Loss";
  }

  public static long ONE_YEAR = Utilities.convertTime("years", 1);
  public static int TW0_YEARS_IN_MONTHS = 24;
  public static int TWENTY_YEARS_IN_MONTHS = 240;

  private static final Map<GrowthChart.ChartType, GrowthChart> growthChart =
      GrowthChart.loadCharts();
  public static final String ACTIVE_WEIGHT_MANAGEMENT = "active_weight_management";
  public static final String PRE_MANAGEMENT_WEIGHT = "pre_management_weight";
  public static final String WEIGHT_MANAGEMENT_START = "weight_management_start";
  public static final String WEIGHT_LOSS_PERCENTAGE = "weight_loss_percentage";
  public static final String WEIGHT_LOSS_BMI_PERCENTILE_CHANGE
      = "weight_loss_bmi_percentile_change";
  public static final String LONG_TERM_WEIGHT_LOSS = "long_term_weight_loss";
  public static final String WEIGHT_LOSS_ADHERENCE = "weight_loss_adherence";

  public static final int managementStartAge = (int) BiometricsConfig.get("min_age", 5);
  public static final double startWeightManagementProb =
      (double) BiometricsConfig.get("start_prob", 0.493);
  public static final double adherence =
      (double) BiometricsConfig.get("adherence", 0.605);
  public static final double startBMI =
      (double) BiometricsConfig.get("start_bmi", 30d);
  public static final double startPercentile =
      (double) BiometricsConfig.get("start_percentile", 0.95d);
  public static final double minLoss = (double) BiometricsConfig.get("min_loss", 0.07);
  public static final double maxLoss = (double) BiometricsConfig.get("max_loss", 0.1);
  public static final double maintenance = (double) BiometricsConfig.get("maintenance", 0.2);
  public static final double maxPedPercentileChange =
      (double) BiometricsConfig.get("max_ped_percentile_change", 0.1);

  public Module clone() {
    return this;
  }

  @Override
  public boolean process(Person person, long time) {
    if (!person.alive(time)) {
      return true;
    }

    Object activeWeightManagement = person.attributes.get(ACTIVE_WEIGHT_MANAGEMENT);

    // First check to see if they are under active weight management
    if (activeWeightManagement != null && (boolean) activeWeightManagement) {
      boolean longTermSuccess = (boolean) person.attributes.get(LONG_TERM_WEIGHT_LOSS);
      int age = person.ageInYears(time);
      // In the first year of management, if there is adherence, the person will lose
      // weight
      if (firstYearOfManagement(person, time)) {
        manageFirstYearWeight(person, time);
      } else if (firstFiveYearsOfManagement(person, time)) {
        manageYearTwoThroughFive(person, time);
      } else {
        // five years after the start
        if (longTermSuccess) {
          if (age < 20) {
            // The person will continue to grow, increase their weight, but keep BMI steady
            maintainBMIPercentile(person, time);
          }
        } else {
          stopWeightManagement(person);
        }
      }
    } else {
      boolean willStart = willStartWeightManagement(person, time);
      if (willStart) {
        startWeightManagement(person, time);
      }
    }
    return false;
  }

  /**
   * This method handles all weight management cases (adherent and non-adherent) for the first year
   * of weight management.
   */
  public void manageFirstYearWeight(Person person, long time) {
    boolean followsPlan = (boolean) person.attributes.get(WEIGHT_LOSS_ADHERENCE);
    int age = person.ageInYears(time);
    if (age < 20) {
      // For pediatric cases, weight adjustment will still be handled by the Lifecycle module.
      // However, if a person follows the plan, this module will adjust the BMI vector to create
      // the necessary drop in BMI percentile.
      if (followsPlan) {
        adjustBMIVectorForSuccessfulManagement(person);
      }
    } else {
      double weight;
      if (followsPlan) {
        weight = adultWeightLoss(person, time);
      } else {
        // Not following the plan. Just keep the weight steady
        weight = person.getVitalSign(VitalSign.WEIGHT, time);
      }
      double height = person.getVitalSign(VitalSign.HEIGHT, time);
      person.setVitalSign(VitalSign.WEIGHT, weight);
      person.setVitalSign(VitalSign.BMI, calculate(height, weight));
    }
  }

  /**
   * This method handles all weight management cases (adherent and non-adherent) for the year two
   * through year five of weight management.
   */
  public void manageYearTwoThroughFive(Person person, long time) {
    boolean followsPlan = (boolean) person.attributes.get(WEIGHT_LOSS_ADHERENCE);
    boolean longTermSuccess = (boolean) person.attributes.get(LONG_TERM_WEIGHT_LOSS);
    int age = person.ageInYears(time);
    String gender = (String) person.attributes.get(Person.GENDER);
    // In the next 5 years, if someone has lost weight, check to see if they
    // will have long term success. If they don't, revert their weight back
    // to the original weight or BMI percentile
    if (followsPlan) {
      if (longTermSuccess) {
        if (age < 20) {
          // The person will continue to grow, increase their weight, but keep BMI steady
          maintainBMIPercentile(person, time);
        }
      } else {
        if (age < 20) {
          pediatricRegression(person, time);
        } else {
          double weight;
          double height;
          long start = (long) person.attributes.get(WEIGHT_MANAGEMENT_START);
          if (person.ageInYears(start) < 20) {
            height = growthChart.get(GrowthChart.ChartType.HEIGHT).lookUp(240, gender,
                person.getVitalSign(VitalSign.HEIGHT_PERCENTILE, time));
            weight = transitionRegression(person, time);
          } else {
            height = person.getVitalSign(VitalSign.HEIGHT, time);
            weight = adultRegression(person, time);
          }
          person.setVitalSign(VitalSign.HEIGHT, height);
          person.setVitalSign(VitalSign.WEIGHT, weight);
          person.setVitalSign(VitalSign.BMI, calculate(height, weight));
        }
      }
    }
  }

  /**
   * Person stops weight management. The module will remove all weight management related
   * attributes.
   */
  public void stopWeightManagement(Person person) {
    person.attributes.remove(WEIGHT_MANAGEMENT_START);
    person.attributes.remove(WEIGHT_LOSS_PERCENTAGE);
    person.attributes.remove(WEIGHT_LOSS_ADHERENCE);
    person.attributes.remove(WEIGHT_LOSS_BMI_PERCENTILE_CHANGE);
    person.attributes.remove(PRE_MANAGEMENT_WEIGHT);
    person.attributes.remove(LONG_TERM_WEIGHT_LOSS);
    person.attributes.put(ACTIVE_WEIGHT_MANAGEMENT, false);
  }

  /**
   * Determines whether the person is currently within their first year of active weight management
   * based on the WEIGHT_MANAGEMENT_START attribute.
   */
  public boolean firstYearOfManagement(Person person, long time) {
    long start = (long) person.attributes.get(WEIGHT_MANAGEMENT_START);
    return start >= time - Utilities.convertTime("years", 1);
  }

  /**
   * Determines whether the person is currently within their first five years of active weight
   * management based on the WEIGHT_MANAGEMENT_START attribute.
   */
  public boolean firstFiveYearsOfManagement(Person person, long time) {
    long start = (long) person.attributes.get(WEIGHT_MANAGEMENT_START);
    return start >= time - Utilities.convertTime("years", 5);
  }

  /**
   * Weight loss is linear from the person's start weight to their target
   * weight (start - percentage loss) over the first year of active weight management.
   * Returns the new weight for the person.
   */
  public double adultWeightLoss(Person person, long time) {
    long start = (long) person.attributes.get(WEIGHT_MANAGEMENT_START);
    double year = Utilities.convertTime("years", 1);
    double percentOfYearElapsed = (time - start) / year;
    double startWeight = (double) person.attributes.get(PRE_MANAGEMENT_WEIGHT);
    double lossPercent = (double) person.attributes.get(WEIGHT_LOSS_PERCENTAGE);
    return startWeight - (startWeight * lossPercent * percentOfYearElapsed);
  }

  /**
   * Weight regression is linear from a person's current weight to their original weight over the
   * second through fifth year of active weight management. Returns the new weight for the person.
   */
  public double adultRegression(Person person, long time) {
    long start = (long) person.attributes.get(WEIGHT_MANAGEMENT_START);
    double percentOfTimeElapsed = (time - start - Utilities.convertTime("years", 1))
        / (double) Utilities.convertTime("years", 4);
    double startWeight = (double) person.attributes.get(PRE_MANAGEMENT_WEIGHT);
    double lossPercent = (double) person.attributes.get(WEIGHT_LOSS_PERCENTAGE);
    double minWeight = startWeight - (startWeight * lossPercent);
    return startWeight - ((startWeight - minWeight) * (1 - percentOfTimeElapsed));
  }

  /**
   * This will regress a pediatric patient back to their BMI percentile. Weight gain will not
   * necessarily be linear. It will approach the BMI based on percentile at age as a function
   * of time in the regression period.
   */
  public void pediatricRegression(Person person, long time) {
    PediatricGrowthTrajectory pgt =
        (PediatricGrowthTrajectory) person.attributes.get(Person.GROWTH_TRAJECTORY);
    long start = (long) person.attributes.get(WEIGHT_MANAGEMENT_START);
    int startAgeInMonths = person.ageInMonths(start);
    if (time + ONE_YEAR > pgt.tail().timeInSimulation) {
      GrowthChart bmiChart = growthChart.get(GrowthChart.ChartType.BMI);
      String gender = (String) person.attributes.get(Person.GENDER);
      double bmiAtStart = pgt.currentBMI(person, start);
      double originalPercentile = bmiChart.percentileFor(startAgeInMonths, gender, bmiAtStart);
      double percentileChange = (double) person.attributes.get(WEIGHT_LOSS_BMI_PERCENTILE_CHANGE);
      int nextAgeInMonths = pgt.tail().ageInMonths + 12;
      if (nextAgeInMonths > 240) {
        nextAgeInMonths = 240;
      }
      long nextTimeInSimulation = pgt.tail().timeInSimulation + ONE_YEAR;
      int yearsOfRegression = (nextAgeInMonths - startAgeInMonths - TW0_YEARS_IN_MONTHS) / 12;
      double regressionPeriodYears = 5;
      double nextPercentile = originalPercentile - percentileChange
          * (1d - (yearsOfRegression / regressionPeriodYears));
      pgt.addPointFromPercentile(nextAgeInMonths, nextTimeInSimulation, nextPercentile, gender);
    }
  }


  /**
   * Revert the person to their 240 month weight percentile following the same procedure as
   * pediatric regression.
   */
  public double transitionRegression(Person person, long time) {
    GrowthChart bmiChart = growthChart.get(GrowthChart.ChartType.BMI);
    PediatricGrowthTrajectory pgt =
        (PediatricGrowthTrajectory) person.attributes.get(Person.GROWTH_TRAJECTORY);
    long start = (long) person.attributes.get(WEIGHT_MANAGEMENT_START);
    int startAgeInMonths = person.ageInMonths(start);
    double bmiAtStart = pgt.currentBMI(person, start);
    String gender = (String) person.attributes.get(Person.GENDER);
    double originalPercentile = bmiChart.percentileFor(startAgeInMonths, gender, bmiAtStart);
    double bmiForPercentileAtTwenty = bmiChart.lookUp(240, gender, originalPercentile);
    double height = growthChart.get(GrowthChart.ChartType.HEIGHT).lookUp(240, gender,
        person.getVitalSign(VitalSign.HEIGHT_PERCENTILE, time));
    double targetWeight = BMI.weightForHeightAndBMI(height, bmiForPercentileAtTwenty);
    int ageTwenty = 20;
    int lossAndRegressionTotalYears = 7;
    double weightAtTwenty = BMI.weightForHeightAndBMI(height, pgt.tail().bmi);
    int regressionEndAge = (startAgeInMonths / 12) + lossAndRegressionTotalYears;
    double percentageElapsed = (person.ageInDecimalYears(time) - ageTwenty)
        / (regressionEndAge - ageTwenty);
    return weightAtTwenty + (percentageElapsed * (targetWeight - weightAtTwenty));
  }

  /**
   * Change the BMI vector to reflect successful management of weight. This will add a new point at
   * the end of the trajectory that will result in the BMI percentile change selected for the
   * person. The change will take place 12 months after the current tail of the trajectory. If
   * that will take the person past 20 years old, the time frame will be cut short to end at 240
   * months of age.
   * @param person to adjust the trajectory for
   */
  public void adjustBMIVectorForSuccessfulManagement(Person person) {
    GrowthChart bmiChart = growthChart.get(GrowthChart.ChartType.BMI);
    String gender = (String) person.attributes.get(Person.GENDER);
    long start = (long) person.attributes.get(WEIGHT_MANAGEMENT_START);
    int startAgeInMonths = person.ageInMonths(start);
    PediatricGrowthTrajectory pgt =
        (PediatricGrowthTrajectory) person.attributes.get(Person.GROWTH_TRAJECTORY);
    double percentileChange = (double) person.attributes.get(WEIGHT_LOSS_BMI_PERCENTILE_CHANGE);
    double bmiAtStart = pgt.currentBMI(person, start);
    double startPercentile = bmiChart.percentileFor(startAgeInMonths, gender, bmiAtStart);
    int currentTailAge = pgt.tail().ageInMonths;
    double currentTailBMI = pgt.tail().bmi;
    double currentTailPercentile = bmiChart.percentileFor(currentTailAge, gender, currentTailBMI);
    if (currentTailPercentile <= startPercentile) {
      // Vector has been adjusted, exit early to not run again.
      return;
    }
    double targetPercentile = currentTailPercentile - percentileChange;
    long currentTailTimeInSim = pgt.tail().timeInSimulation;
    int monthsInTheFuture = 12;
    if (currentTailAge + monthsInTheFuture > TWENTY_YEARS_IN_MONTHS) {
      monthsInTheFuture = TWENTY_YEARS_IN_MONTHS - currentTailAge;
      targetPercentile = currentTailPercentile
          - (percentileChange * ((double) monthsInTheFuture) / 12);
    }
    double targetBMI = bmiChart.lookUp(currentTailAge + monthsInTheFuture,
        gender, targetPercentile);
    pgt.addPoint(currentTailAge + monthsInTheFuture,
        currentTailTimeInSim + Utilities.convertTime("months", monthsInTheFuture), targetBMI);
  }

  /**
   * For patients under 20 that have successful weight management and long term success, they will
   * maintain their BMI until they reach age 20. This means that they will gain weight as they are
   * gaining height, but it will be in a more healthy range.
   */
  public void maintainBMIPercentile(Person person, long time) {
    GrowthChart bmiChart = growthChart.get(GrowthChart.ChartType.BMI);
    PediatricGrowthTrajectory pgt =
        (PediatricGrowthTrajectory) person.attributes.get(Person.GROWTH_TRAJECTORY);
    int ageInMonths = person.ageInMonths(time);
    PediatricGrowthTrajectory.Point tail = pgt.tail();
    if (ageInMonths < TWENTY_YEARS_IN_MONTHS && tail.ageInMonths <= ageInMonths) {
      String gender = (String) person.attributes.get(Person.GENDER);
      int monthsInTheFuture = 12;
      if (tail.ageInMonths + monthsInTheFuture > TWENTY_YEARS_IN_MONTHS) {
        monthsInTheFuture = TWENTY_YEARS_IN_MONTHS - tail.ageInMonths;
      }

      double percentile = bmiChart.percentileFor(tail.ageInMonths, gender, tail.bmi);
      double nextYearBMI = bmiChart.lookUp(tail.ageInMonths + monthsInTheFuture,
          gender, percentile);
      pgt.addPoint(tail.ageInMonths + monthsInTheFuture,
          tail.timeInSimulation + Utilities.convertTime("months", monthsInTheFuture), nextYearBMI);
    }
  }

  /**
   * Starts active weight management for the person. It will select if a person adheres to their
   * weight management plan. If they do, it will select the percentage of their body weight that
   * they will lose and whether they will keep it off long term.
   */
  public void startWeightManagement(Person person, long time) {
    double startWeight = person.getVitalSign(VitalSign.WEIGHT, time);
    person.attributes.put(ACTIVE_WEIGHT_MANAGEMENT, true);
    person.attributes.put(PRE_MANAGEMENT_WEIGHT, startWeight);
    person.attributes.put(WEIGHT_MANAGEMENT_START, time);
    boolean stickToPlan = person.rand() <= adherence;
    person.attributes.put(WEIGHT_LOSS_ADHERENCE, stickToPlan);
    if (stickToPlan) {
      if (person.ageInYears(time) >= 20) {
        double minLossPercentage = minLoss;
        double maxLossPercentage = maxLoss;
        if (person.attributes.get(Person.TARGET_WEIGHT_LOSS) != null) {
          double targetWeightLoss = Double.valueOf((int)person.attributes.get(Person.TARGET_WEIGHT_LOSS));
          minLossPercentage = targetWeightLoss;
          maxLossPercentage = targetWeightLoss;
        }
        double percentWeightLoss = person.rand(minLossPercentage, maxLossPercentage);
        person.attributes.put(WEIGHT_LOSS_PERCENTAGE, percentWeightLoss);
      } else {
        double bmiPercentileChange = person.rand() * maxPedPercentileChange;
        person.attributes.put(WEIGHT_LOSS_BMI_PERCENTILE_CHANGE, bmiPercentileChange);
      }
      boolean longTermSuccess = person.rand() <= maintenance;
      person.attributes.put(LONG_TERM_WEIGHT_LOSS, longTermSuccess);
    } else {
      person.attributes.put(LONG_TERM_WEIGHT_LOSS, false);
    }
  }

  /**
   * Determines whether a person will start weight management. If they meet the weight
   * management thresholds, there is a 49.3% chance that they will start
   * weight management. This does not mean that they will adhere to the management plan.
   */
  public boolean willStartWeightManagement(Person person, long time) {
    if (meetsWeightManagementThresholds(person, time)) {
      return person.rand() <= startWeightManagementProb;
    }
    return false;
  }

  /**
   * Determines whether a person meets the thresholds for starting weight management.
   * With the default settings:
   * Children under 5 do not ever meet the threshold.
   * Patients from ages 5 to 20 meet the threshold if their BMI is at or over the 95th percentile
   *   for their age in months
   * Patients 20 and older meet the threshold if their BMI is 30 or over.
   */
  public boolean meetsWeightManagementThresholds(Person person, long time) {
    int age = person.ageInYears(time);
    // TODO: Find a better approach
    // If someone is 19, don't start weight management as we don't have a good way to transition
    // weight loss from the growth charts (BMI Percentile) to percentage of body weight.
    if (age == 19) {
      return false;
    }
    double bmi = person.getVitalSign(VitalSign.BMI, time);
    double bmiAtPercentile = 500; // initializing to an impossibly high value
    // if we somehow hit this later
    if (age >= 2 && age < 20) {
      int ageInMonths = person.ageInMonths(time);
      String gender = (String) person.attributes.get(Person.GENDER);
      bmiAtPercentile = growthChart.get(GrowthChart.ChartType.BMI).lookUp(ageInMonths, gender,
           startPercentile);
    }
    return (age >= managementStartAge && ((bmi >= startBMI && age >= 20)
        || (age < 20 && bmi >= bmiAtPercentile)));
  }

}
