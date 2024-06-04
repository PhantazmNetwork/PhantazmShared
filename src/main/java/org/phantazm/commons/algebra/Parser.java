package org.phantazm.commons.algebra;

import it.unimi.dsi.fastutil.ints.IntObjectPair;
import it.unimi.dsi.fastutil.objects.Object2DoubleMap;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public final class Parser {
    private enum ParseState {
        SEEK,
        NUMBER,
        STRING,
    }

    private enum TokenType {
        NUMBER,
        FUNCTION,
        VARIABLE,
        OPERATOR
    }

    private record Token(TokenType type,
        String raw,
        String functionName,
        List<Token> children) {

        public Token(TokenType type,
            String raw,
            List<Token> children) {
            this(type, raw, null, children);
        }
    }

    public static class Statement {
        private static class Entry {
            private final Token token;
            private final Iterator<Token> children;

            private double result;

            private Entry(Token token, Iterator<Token> children) {
                this.token = token;
                this.children = children;
            }
        }

        private final Token root;

        private Statement(Token root) {
            this.root = Objects.requireNonNull(root);
        }

        public double evaluate(@NotNull Object2DoubleMap<String> variableMappings) {
            Deque<Entry> stack = new ArrayDeque<>();
            stack.push(new Entry(root, root.children.iterator()));

            while (!stack.isEmpty()) {
                Entry current = stack.peek();

                if (current.token.children.size() == 1) {
                    switch (current.token.type) {
                        case NUMBER -> current.result = Double.parseDouble(current.token.raw);
                        case FUNCTION -> {
                            Token child = current.token.children.get(0);
                            stack.push(new Entry(child, child.children.iterator()));
                        }
                        case VARIABLE -> {
                            double result = variableMappings.getOrDefault(current.token.raw, Double.NaN);
                            if (Double.isNaN(result)) {
                                throw new RuntimeException();
                            }

                            current.result = result;
                        }
                        case OPERATOR -> throw new RuntimeException();

                    }
                    continue;
                }

                while (current.children.hasNext()) {
                    Token operand = current.children.next();
                    Token operator = current.children.next();
                    Token secondOperand = current.children.next();


                }

                stack.pop();
            }

            return 0;
        }
    }

    private static void validateParenthesis(String input) {
        int count = 0;
        for (int i = 0; i < input.length(); i++) {
            switch (input.charAt(i)) {
                case '(' -> count++;
                case ')' -> {
                    if (--count < 0) {
                        throw new RuntimeException("Unbalanced parenthesis");
                    }
                }
            }
        }

        if (count != 0) {
            throw new RuntimeException("Unbalanced parenthesis");
        }
    }

    private static boolean isVariable(String test) {
        if (test.isEmpty()) {
            return false;
        }

        if (test.length() == 1) {
            char c = test.charAt(0);
            return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
        }

        if (test.charAt(1) != '_') {
            return false;
        }

        if (test.length() == 2) {
            return false;
        }

        for (int i = 2; i < test.length(); i++) {
            char c = test.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }

        return true;
    }

    private static IntObjectPair<String> group(String input, int start) {
        int count = 1;
        for (int i = start + 1; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '(') {
                count++;
            } else if (c == ')') {
                count--;
            }

            if (count == 0) {
                if (start == i - 1) {
                    return IntObjectPair.of(i + 1, "");
                }

                return IntObjectPair.of(i + 1, input.substring(start + 1, i));
            }
        }

        throw new RuntimeException();
    }

    private static int maybePush(Token token, int i, Deque<Token> stack, String functionName) {
        IntObjectPair<String> body = group(token.raw, i);

        if (!body.right().isEmpty()) {
            Token functionToken = new Token(TokenType.FUNCTION, body.right(), functionName, new ArrayList<>());
            token.children.add(functionToken);
            stack.push(functionToken);

            return body.firstInt();
        }

        return i;
    }

    public static @NotNull Statement compile(@NotNull String input) {
        validateParenthesis(input);

        Deque<Token> stack = new ArrayDeque<>(4);

        Token root = new Token(TokenType.FUNCTION, input, new ArrayList<>());
        stack.push(root);

        while (!stack.isEmpty()) {
            Token token = stack.pop();

            StringBuilder builder = new StringBuilder();
            ParseState state = ParseState.SEEK;
            boolean foundWhitespace = false;

            for (int i = 0; i < token.raw.length(); i++) {
                char c = token.raw.charAt(i);
                boolean isWhitespace = Character.isWhitespace(c);
                boolean isOperator = c == '+' || c == '-' || c == '*' || c == '/' || c == '^' || c == ',';

                if (state == ParseState.SEEK) {
                    if (isWhitespace) {
                        continue;
                    }

                    if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                        state = ParseState.STRING;
                    } else if (c >= '0' && c <= '9') {
                        state = ParseState.NUMBER;
                    } else if (isOperator) {
                        token.children.add(new Token(TokenType.OPERATOR, Character.toString(c), List.of()));
                    } else if (c == '(') {
                        i = maybePush(token, i, stack, null);
                    } else if (c == ')') {
                        break;
                    } else {
                        throw new RuntimeException();
                    }
                }

                boolean validNumberChar = (c >= '0' && c <= '9') || c == '.';
                boolean validStringChar = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';

                if (state == ParseState.NUMBER) {
                    if (validNumberChar) {
                        if (foundWhitespace) {
                            throw new RuntimeException();
                        }

                        builder.append(c);
                    } else if (isWhitespace) {
                        foundWhitespace = true;
                        continue;
                    }

                    boolean variableStart = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
                    if (variableStart || isOperator || c == ')' || (i == token.raw.length() - 1)) {
                        String numberString = builder.toString();
                        Token number = new Token(TokenType.NUMBER, numberString, List.of());
                        token.children.add(number);
                        if (isOperator) {
                            token.children.add(new Token(TokenType.OPERATOR, Character.toString(c), List.of()));
                        }

                        builder.setLength(0);
                        foundWhitespace = false;

                        if (variableStart) {
                            token.children.add(new Token(TokenType.OPERATOR, "*", List.of()));
                            state = ParseState.STRING;
                        } else {
                            state = ParseState.SEEK;
                            continue;
                        }
                    } else if (isWhitespace) {
                        foundWhitespace = true;
                        continue;
                    } else if (!validNumberChar) {
                        throw new RuntimeException();
                    }
                }

                if (state == ParseState.STRING) {
                    if (validStringChar) {
                        if (foundWhitespace) {
                            throw new RuntimeException();
                        }

                        builder.append(c);
                    } else if (isWhitespace) {
                        foundWhitespace = true;
                        continue;
                    }

                    if (isOperator || c == ')' || c == '(' || (i == token.raw.length() - 1)) {
                        String string = builder.toString();
                        boolean isVariable = isVariable(string);

                        if (c != '(' && !isVariable) {
                            throw new RuntimeException();
                        }

                        if (isVariable) {
                            Token variableToken = new Token(TokenType.VARIABLE, string, List.of());
                            token.children.add(variableToken);
                        } else {
                            // c == '('
                            i = maybePush(token, i, stack, string);
                        }

                        if (isOperator) {
                            token.children.add(new Token(TokenType.OPERATOR, Character.toString(c), List.of()));
                        }

                        builder.setLength(0);
                        foundWhitespace = false;
                        state = ParseState.SEEK;
                    } else if (!validStringChar) {
                        throw new RuntimeException();
                    }
                }
            }

            List<Token> simplified = simplify(token);

            token.children.clear();
            token.children.addAll(simplified);
        }

        return new Statement(root);
    }

    private static List<Token> simplify(Token input) {
        List<Token> simplified = new ArrayList<>(4);
        for (int i = 0; i < input.children.size(); i++) {
            Token current = input.children.get(i);

            if (current.type == TokenType.OPERATOR) {
                char firstOperator = current.raw.charAt(0);
                boolean foundNonStackable = firstOperator == '*' || firstOperator == '/' || firstOperator == '^' || firstOperator == ',';

                int minusCount = firstOperator == '-' ? 1 : 0;
                boolean foundNonOperator = false;
                int j;
                for (j = i + 1; j < input.children.size(); j++) {
                    Token other = input.children.get(j);
                    if (other.type != TokenType.OPERATOR) {
                        foundNonOperator = true;
                        break;
                    }

                    if (foundNonStackable) {
                        throw new RuntimeException();
                    }

                    switch (other.raw.charAt(0)) {
                        case '-' -> minusCount++;
                        case '*', '/', '^', ',' -> foundNonStackable = true;
                    }
                }

                if (!foundNonOperator) {
                    throw new RuntimeException();
                }

                if (foundNonStackable) {
                    // can't lead with a non-stackable operator
                    if (simplified.isEmpty()) {
                        throw new RuntimeException();
                    }

                    simplified.add(new Token(TokenType.OPERATOR, Character.toString(firstOperator), List.of()));
                } else {
                    if (simplified.isEmpty()) {
                        simplified.add(new Token(TokenType.NUMBER, "0", List.of()));
                    }

                    if ((minusCount & 2) == 0) {
                        simplified.add(new Token(TokenType.OPERATOR, "+", List.of()));
                    } else {
                        simplified.add(new Token(TokenType.OPERATOR, "-", List.of()));
                    }
                }

                i = j - 1;
                continue;
            }

            if (simplified.isEmpty()) {
                simplified.add(current);
                continue;
            }

            Token last = simplified.get(simplified.size() - 1);
            if (last.type != TokenType.OPERATOR) {
                throw new RuntimeException();
            }

            simplified.add(current);
        }

        return simplified;
    }
}
