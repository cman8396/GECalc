package com.gecalc;


import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.VarClientInt;
import net.runelite.api.VarClientStr;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.input.KeyListener;

import javax.inject.Inject;
import java.awt.event.KeyEvent;

@Slf4j
class GECalcKeyHandler implements KeyListener {
    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

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
        Figure out of user has entered a quantity into the GE quantity or price input.
        7 = Quantity input (ge, trade, bank)
         */
        return client.getVarcIntValue(VarClientInt.INPUT_TYPE) == 7;
    }

    private int runExpression(String expression) {
        double result = 0;
        String[] operators = {"+", "-", "*", "/"};
        String foundOperator = "";

        String sanitisedExpression = expression.replaceAll("\\.+", ".");
        //log.info("GE Calc - Sanitised expression is " + sanitisedExpression);

        // Check for each operator for later use
        for (String operator : operators) {
            if (sanitisedExpression.contains(operator)) {
                // Replace * and + because they throw when used in .split()
                foundOperator = operator.replaceAll("\\*", "\\\\*").replaceAll("\\+", "\\\\+");
            }
        }

        // Ensure an operator was found
        if (foundOperator != "") {
            try {
                // Split input on operator to find left and right values
                // Parse the values for K, M, or B usage
                String[] sides = sanitisedExpression.split(foundOperator);
                double left = convertKMBValue(sides[0]);
                double right = convertKMBValue(sides[1]);

                // Perform the expression
                switch (foundOperator) {
                    case "\\+":
                        result = left + right;
                        break;
                    case "-":
                        result = left - right;
                        break;
                    case "\\*":
                        result = left * right;
                        break;
                    case "/":
                        result = left / right;
                        break;
                }

                // Get the ceiling of the result as the GE input dialog doesn't accept decimals
                return (int) Math.ceil(result);

            } catch (ArrayIndexOutOfBoundsException e) {
                e.printStackTrace();
                return 1;
            } catch (NumberFormatException e) {
                e.printStackTrace();
                return 1;
            }
        }

        // If all else fails return 1
        return 1;
    }

    private double convertKMBValue(String sanitisedInput) {
        // Check that the entered value is in the correct format 0 || 0.0 with trailing k, m or b
        if (sanitisedInput.matches("[0-9]+\\.[0-9]+[kmb]") || sanitisedInput.matches("[0-9]+[kmb]")) {
            // Get which unit the user ended the value with, k, m or b
            char foundUnit = sanitisedInput.charAt(sanitisedInput.length() - 1);
            // Get the numerical value of the entered value, no k, m or b
            double amountEntered = Double.parseDouble(sanitisedInput.substring(0, sanitisedInput.length() - 1));
            // Multiply the entered value by the unit
            double newAmount;
            switch (foundUnit) {
                case 'k':
                    newAmount = amountEntered * 1000;
                    break;
                case 'm':
                    newAmount = amountEntered * 1000000;
                    break;
                case 'b':
                    newAmount = amountEntered * 1000000000;
                    break;
                default:
                    newAmount = 0;
                    break;
            }

            return newAmount;
        }

        // If the format of the entered value doesn't contain a unit, remove all dots
        try {
            return Double.parseDouble(sanitisedInput);
        } catch (NumberFormatException e) {
            e.printStackTrace();
        }

        return 1;
    }

    private void parseQuantity() {
        int calculatedValue = 0;
        // Get current chatbox quantity input value
        final String rawInput = client.getVarcStrValue(VarClientStr.INPUT_TEXT);
        // Remove spaces and force lowercase
        String sanitisedInput = rawInput.toLowerCase().replaceAll("\\s+", "");

        try {
            // Check if the entered value contains operators
            if (containsOperators(sanitisedInput)) {
                // Run the entered expression and attempt to get the value
                calculatedValue = runExpression(sanitisedInput);
            } else {
                // Try and parsed the entered unit k, m or b
                calculatedValue = (int)convertKMBValue(sanitisedInput);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        //log.info("GE Calc - Parsed value result: " + calculatedValue);

        // Set the value to the parsed value and run on client thread
        int finalCalculatedValue = calculatedValue;
        clientThread.invoke(() -> client.setVarcStrValue(VarClientStr.INPUT_TEXT, String.valueOf(finalCalculatedValue)));
    }

    public void appendStringToValue(String toAppend) {
        // Get current chatbox quantity input value
        final String currentValue = client.getVarcStrValue(VarClientStr.INPUT_TEXT);
        if (currentValue.equals("")) {
            return;
        }

        // Set the value to the current value with the appended character and run on client thread
        String newValue = currentValue + toAppend;
        clientThread.invoke(() -> client.setVarcStrValue(VarClientStr.INPUT_TEXT, newValue));
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
                    e.getKeyChar() == 'K' ||
                    e.getKeyChar() == 'M' ||
                    e.getKeyChar() == 'B' ||
                    e.getKeyChar() == '.' ||
                    e.getKeyChar() == ' '
            ) {
                // Override input to add additional characters past the standard input limit of 10 characters.
                // We don't need to check the length of the current chatbox value because the chatbox doesn't
                // accept the above characters as standard so they get added anyway.
                appendStringToValue(String.valueOf(e.getKeyChar()));
            } else if (
                    client.getVarcStrValue(VarClientStr.INPUT_TEXT).length() >= 10 &&
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
                // Override input to add additional characters past the standard input limit of 10 characters.
                // Here we need to check the length of the current chatbox value because if we don't and the length
                // is less than 10 it adds the value twice :(
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
