import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class AusPost {
   public static void main(String[] args) {
      // Creating BufferedReader to read input from the user
      BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
      DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd MM yyyy");
      List<LocalDate> validDates = new ArrayList<>();

      try {
         // Reading input from the user eg:  02 03 1991, 02 04 1992, 02 02 1990
         System.out.println("Enter dates in the format dd mm yyyy, dd mm yyyy:");
         String input = reader.readLine();

         // Splitting the input line by commas
         String[] dates = input.split(",");

         for (String date : dates) {
            date = date.trim(); // Trim leading and trailing whitespace

            // Checking the length of the string
            if (date.length() == 10 &&
                date.charAt(2) == ' ' &&
                date.charAt(5) == ' ' &&
                isDigit(date.substring(0, 2)) &&
                isDigit(date.substring(3, 5)) &&
                isDigit(date.substring(6))) {

               int year = Integer.parseInt(date.substring(6));
               if (year >= 1900 && year <= 2020) {
                  // Parse the date and add to the list of valid dates
                  LocalDate localDate = LocalDate.parse(date, formatter);
                  validDates.add(localDate);
               } else {
                  System.out.println("Date " + date + " is not valid: Year out of range");
               }
            } else {
               System.out.println("Date " + date + " is not valid: Incorrect format");
            }
         }

         // Sort the valid dates
         Collections.sort(validDates);

         // Display valid dates and differences
         for (int i = 0; i < validDates.size(); i++) {
            System.out.println("Date " + (i + 1) + ": " + validDates.get(i).format(formatter));
            if (i > 0) {
               LocalDate earlierDate = validDates.get(i - 1);
               LocalDate laterDate = validDates.get(i);

               long daysBetween = ChronoUnit.DAYS.between(earlierDate, laterDate);
               long monthsBetween = ChronoUnit.MONTHS.between(earlierDate, laterDate);
               long yearsBetween = ChronoUnit.YEARS.between(earlierDate, laterDate);

               System.out.println("Difference with previous date: " + daysBetween + " days, " + monthsBetween + " months, " + yearsBetween + " years");
            }
         }

      } catch (IOException e) {
         e.printStackTrace();
      }
   }

   // Helper method to check if a string contains only digits
   public static boolean isDigit(String str) {
      for (int i = 0; i < str.length(); i++) {
         if (!Character.isDigit(str.charAt(i))) {
            return false;
         }
      }
      return true;
   }
}
