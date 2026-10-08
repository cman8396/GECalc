package com.gecalc;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.gameval.VarClientID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.input.KeyListener;

import javax.inject.Inject;
import java.awt.event.KeyEvent;
import java.math.BigDecimal;
import java.math.RoundingMode;

@Slf4j
class GECalcKeyHandler implements KeyListener {
    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    private long beyondMaxCash = 2149631130647L;

    private static boolean containsOperators(String inputString) {
        // Quick check to see if entered value contains an operator
        if (inputString.contains("+"))
            return true;
        if (inputString.contains("-"))
            return true;
        if (inputString.contains("*"))
            return true;
        if (inputString.contains("/"))
            return true;

        return false;
    }

    public boolean isQuantityInput() {
        /*
            Figure out if user has entered a quantity into the GE quantity or price input.
            7 = Quantity input (GE, banks, trades)
            30 = GE price input

            Since "GE Improvements: Beyond Max Cash" update on 30 Sep 2026 the GE price
            input field now has a different ID, I assume this is because it goes to 2.147t
         */

        // log.debug("VarClientID.MESLAYERMODE - {}", client.getVarcIntValue(VarClientID.MESLAYERMODE));
        return client.getVarcIntValue(VarClientID.MESLAYERMODE) == 7 || client.getVarcIntValue(VarClientID.MESLAYERMODE) == 30;
    }

    private long runExpression(String expression) {
        BigDecimal result = new BigDecimal(0);
        String[] operators = {"+", "-", "*", "/"};
        String foundOperator = "";

        String sanitisedExpression = expression.replaceAll("\\.+", ".");
        // log.debug("GE Calc - Sanitised expression is {}", sanitisedExpression);

        // Check for each operator for later use
        for (String operator : operators) {
            if (sanitisedExpression.contains(operator)) {
                // Replace * and + because they throw when used in .split()
                foundOperator = operator.replaceAll("\\*", "\\\\*").replaceAll("\\+", "\\\\+");
            }
        }

        // Ensure an operator was found
        if (!foundOperator.isEmpty()) {
            try {
                // Split input on operator to find left and right values
                // Parse the values for K, M, or B usage
                String[] sides = sanitisedExpression.split(foundOperator);
                BigDecimal left = convertKMBValue(sides[0]);
                BigDecimal right = convertKMBValue(sides[1]);

                // Perform the expression
                switch (foundOperator) {
                    case "\\+":
                        result = left.add(right);
                        break;
                    case "-":
                        result = left.subtract(right);
                        break;
                    case "\\*":
                        result = left.multiply(right);
                        break;
                    case "/":
                        result = left.divide(right, RoundingMode.CEILING);
                        break;
                }

                // Get the ceiling of the result as the GE input dialog doesn't accept decimals
                return result.longValue();

            } catch (ArrayIndexOutOfBoundsException | NumberFormatException e) {
                e.printStackTrace();
                return 1;
            }
        }

        // If all else fails return 1
        return 1;
    }

    private BigDecimal convertKMBValue(String sanitisedInput) {
        // Check that the entered value is in the correct format 0 || 0.0 with trailing k, m, b or t
        if (sanitisedInput.matches("[0-9]+\\.[0-9]+[kmbt]") || sanitisedInput.matches("[0-9]+[kmbt]")) {
            // Get which unit the user ended the value with, k, m, b or t
            char foundUnit = sanitisedInput.charAt(sanitisedInput.length() - 1);
            // Get the numerical value of the entered value, no k, m, b or t
            BigDecimal amountEntered = new BigDecimal(sanitisedInput.substring(0, sanitisedInput.length() - 1));
            // Multiply the entered value by the unit
            BigDecimal newAmount;
            switch (foundUnit) {
                case 'k':
                    newAmount = amountEntered.multiply(BigDecimal.valueOf(1000));
                    break;
                case 'm':
                    newAmount = amountEntered.multiply(BigDecimal.valueOf(1000000));
                    break;
                case 'b':
                    newAmount = amountEntered.multiply(BigDecimal.valueOf(1000000000));
                    break;
                case 't':
                    newAmount = amountEntered.multiply(BigDecimal.valueOf(1000000000000L));
                    break;
                default:
                    newAmount = BigDecimal.valueOf(0);
                    break;
            }

            return newAmount;
        }

        // If the format of the entered value doesn't contain a unit, remove all dots
        try {
            return new BigDecimal(sanitisedInput);
        } catch (NumberFormatException e) {
            e.printStackTrace();
        }

        return BigDecimal.valueOf(1);
    }

    private void parseQuantity() {
        long calculatedValue = 0;
        // Get current chatbox quantity input value
        final String rawInput = client.getVarcStrValue(VarClientID.MESLAYERINPUT);
        // Remove spaces and force lowercase
        String sanitisedInput = rawInput.toLowerCase().replaceAll("\\s+", "");

        try {
            // Check if the entered value contains operators
            if (containsOperators(sanitisedInput)) {
                // Run the entered expression and attempt to get the value
                calculatedValue = runExpression(sanitisedInput);
            } else {
                // Try and parse the entered unit k, m, b or t
                calculatedValue = convertKMBValue(sanitisedInput).longValue();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // log.debug("GE Calc - Parsed value result: {}", calculatedValue);

        // Looks like the GE UI only displays up to new max cash value 2.149t (2,149,631,130,647)
        long finalCalculatedValue = Math.min(calculatedValue, beyondMaxCash);
        // Set the value to the parsed value and run on client thread
        clientThread.invoke(() -> client.setVarcStrValue(VarClientID.MESLAYERINPUT, String.valueOf(finalCalculatedValue)));
    }

    public void appendStringToValue(String toAppend) {
        // Get current chatbox quantity input value
        final String currentValue = client.getVarcStrValue(VarClientID.MESLAYERINPUT);
        if (currentValue.isEmpty()) {
            return;
        }

        // Set the value to the current value with the appended character and run on client thread
        String newValue = currentValue + toAppend;
        clientThread.invoke(() -> client.setVarcStrValue(VarClientID.MESLAYERINPUT, newValue));
    }

    @Override
    public void keyPressed(KeyEvent e) {
        // Check if chatbox quantity input is open
        if (isQuantityInput()) {
            if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                // Intercept calculated quantity and parse
                parseQuantity();
            } else if (
                    e.getKeyChar() == '+' ||
                    e.getKeyChar() == '-' ||
                    e.getKeyChar() == '*' ||
                    e.getKeyChar() == '/' ||
                    e.getKeyChar() == 'k' ||
                    e.getKeyChar() == 'm' ||
                    e.getKeyChar() == 'b' ||
                    e.getKeyChar() == 't' ||
                    e.getKeyChar() == 'K' ||
                    e.getKeyChar() == 'M' ||
                    e.getKeyChar() == 'B' ||
                    e.getKeyChar() == 'T' ||
                    e.getKeyChar() == '.' ||
                    e.getKeyChar() == ' '
            ) {
                // Override input to add additional characters past the standard input limit of 19 characters.
                // We don't need to check the length of the current chatbox value because the chatbox doesn't
                // accept the above characters as standard so they get added anyway.
                appendStringToValue(String.valueOf(e.getKeyChar()));
            } else if (
                    client.getVarcStrValue(VarClientID.MESLAYERINPUT).length() >= 19 &&
                    (e.getKeyChar() == '1' ||
                    e.getKeyChar() == '2' ||
                    e.getKeyChar() == '3' ||
                    e.getKeyChar() == '4' ||
                    e.getKeyChar() == '5' ||
                    e.getKeyChar() == '6' ||
                    e.getKeyChar() == '7' ||
                    e.getKeyChar() == '8' ||
                    e.getKeyChar() == '9' ||
                    e.getKeyChar() == '0')
            ) {
                // Override input to add additional characters past the standard input limit of 19 characters.
                // Here we need to check the length of the current chatbox value because if we don't and the length
                // is less than 19 it adds the value twice :(
                appendStringToValue(String.valueOf(e.getKeyChar()));
            }
        }
    }

    @Override
    public void keyReleased(KeyEvent e) {
    }

    @Override
    public void keyTyped(KeyEvent e) {
    }
}
