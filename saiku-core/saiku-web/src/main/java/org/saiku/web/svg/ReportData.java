package org.saiku.web.svg;

import java.util.ArrayList;
import org.saiku.olap.dto.resultset.AbstractBaseCell;
import org.saiku.olap.dto.resultset.MemberCell;

public class ReportData {
    private AbstractBaseCell[][] rowHeader;
    private AbstractBaseCell[][] rowBody;

    public AbstractBaseCell[][] getRowHeader() {
        return rowHeader;
    }

    public void setRowHeader(AbstractBaseCell[][] rowHeader) {
        this.rowHeader = rowHeader;
    }

    public AbstractBaseCell[][] getRowBody() {
        return rowBody;
    }

    public void setRowBody(AbstractBaseCell[][] rowBody) {
        this.rowBody = rowBody;
    }

    class Section {
        private String des;
        private ArrayList<Section> child = new ArrayList<>();
        private Section parent;
        private AbstractBaseCell[][] data;
        private ArrayList<String> head;

        public ArrayList<String> getHead() {
            return head;
        }

        public void setHead(ArrayList<String> head) {
            this.head = head;
        }

        public String getDes() {
            return des;
        }

        public void setDes(String des) {
            this.des = des;
        }

        public ArrayList<Section> getChild() {
            return child;
        }

        public void setChild(ArrayList<Section> child) {
            this.child = child;
        }

        public Section getParent() {
            return parent;
        }

        public void setParent(Section parent) {
            this.parent = parent;
        }

        public AbstractBaseCell[][] getData() {
            return data;
        }

        public void setData(AbstractBaseCell[][] data) {
            this.data = data;
        }
    }

    public int dimTab(AbstractBaseCell[][] rowBody, AbstractBaseCell[][] rowHeader) {
        int dim = 0;

        for (int j = 0; j < rowBody[0].length - 1; j++) {
            if (rowBody[0][j].getClass().equals(MemberCell.class)) {
                dim++;
            }
        }
        return dim;
    }

    private ArrayList<Section> section(AbstractBaseCell[][] dataMatrix, int dimIndex, int dim, Section parent) {
        ArrayList<Section> sections = new ArrayList<>();
        Section section = null;

        for (int i = 0; i < dataMatrix.length; i++) {
            if (dataMatrix[i][dimIndex].getFormattedValue() != null) {
                section = new Section();

                int start = i;
                i++;
                while (i < dataMatrix.length && dataMatrix[i][dimIndex].getFormattedValue() == null) {
                    i++;
                }
                if (dim == 1) i = dataMatrix.length;
                int row = i - start;
                if (dataMatrix[0][dim - 1].getClass().equals(MemberCell.class)
                        && dataMatrix[0][dim - 1].getFormattedValue() == null
                        && dataMatrix[0][0].getParentDimension() == (dataMatrix[1][dim - 1].getParentDimension())) {
                    row = row - 1;
                }
                AbstractBaseCell[][] abc = new AbstractBaseCell[row][dataMatrix[start].length];
                int z = 0;
                for (int j = start; j < i; j++) {

                    for (int t = 0; t < dataMatrix[start].length; t++) {

                        if (row != i - start && !dataMatrix[j][dim].getClass().equals(MemberCell.class) && j == start)
                            j++;
                        abc[z][t] = dataMatrix[j][t];
                    }
                    z++;
                }
                int temp = dim;
                if (dim > 1) {
                    section.setDes(dataMatrix[start][dimIndex].getFormattedValue());
                    temp = dim - 1;
                }
                if (dimIndex == temp - 1) {
                    section.setData(abc);
                    section.setHead(parent.getHead());
                    section.setParent(parent);
                } else {
                    section.setHead(parent.getHead());
                    parent = section;
                }
                if (section.getData() == null) section.setChild(section(abc, dimIndex + 1, dim, parent));
                sections.add(section);
                i--;
            } else {
                // Scorre
            }
        }

        return sections;
    }

    public ArrayList<Section> section(
            AbstractBaseCell[][] dataMatrix, AbstractBaseCell[][] headMatrix, int rowIndex, int dim, Section parent) {
        ArrayList<Section> sections = new ArrayList<>();

        Section section = null;
        if (rowHeader.length == 1) {
            section = new Section();
            ArrayList<String> head = new ArrayList<>();
            for (int j = dim - 1; j < headMatrix[headMatrix.length - 1].length; j++) {
                head.add(headMatrix[headMatrix.length - 1][j].getFormattedValue());
            }
            section.setHead(head);
            section.setChild(section(dataMatrix, 0, dim, section));
            sections.add(section);
        } else {
            for (int i = 1; i < headMatrix[0].length; i++) {
                if (headMatrix[rowIndex][i] != null)
                    if (!headMatrix[rowIndex][i].getFormattedValue().equals("MeasuresLevel")
                            && headMatrix[rowIndex][i].getFormattedValue() != null) {
                        section = new Section();
                        section.setDes(headMatrix[rowIndex][i].getFormattedValue());
                        sections.add(section);

                        int start = i;
                        Boolean tot = false;
                        if (i < headMatrix[0].length - 1 && headMatrix[rowIndex][i + 1].getFormattedValue() == null) {
                            tot = true;
                            while (i < headMatrix[rowIndex].length - 1
                                    && headMatrix[rowIndex][i + 1].getFormattedValue() == null) {
                                i++;
                            }

                        } else {
                            while (i < headMatrix[rowIndex].length - 1
                                    && headMatrix[rowIndex][i]
                                            .getFormattedValue()
                                            .equals(headMatrix[rowIndex][i + 1].getFormattedValue())) {
                                i++;
                            }
                        }
                        AbstractBaseCell[][] headm =
                                new AbstractBaseCell[headMatrix.length - rowIndex - 1][i - start + dim + 1];
                        for (int j = 0; j < headMatrix.length - rowIndex - 1; j++) {
                            for (int t = start; t <= i; t++) {
                                if (headMatrix[j][t] != null) headm[j][t - start + dim] = headMatrix[j + 1][t];
                            }
                        }

                        // body child
                        AbstractBaseCell[][] datarow = new AbstractBaseCell[dataMatrix.length][i - start + dim + 1];
                        for (int j = 0; j < dataMatrix.length; j++) {
                            System.arraycopy(dataMatrix[j], 0, datarow[j], 0, dim);
                            for (int t = start; t <= i; t++) {
                                if (dataMatrix[j][t] != null) datarow[j][t - start + dim] = dataMatrix[j][t];
                            }
                        }
                        ArrayList<String> head = new ArrayList<>();

                        head.add(rowHeader[rowHeader.length - 1][dim - 1].getFormattedValue());
                        int k = 0;
                        for (int j = start; j <= i; j++) {
                            if (j != 0)
                                if (tot && k == 0) {
                                    head.add("Totale");
                                    k = 1;
                                } else head.add(headMatrix[headMatrix.length - 1][j].getFormattedValue());
                        }
                        if (rowIndex == headMatrix.length - 2) {
                            section.setParent(parent);
                            section.setHead(head);
                            section.setChild(section(datarow, 0, dim, section));
                            parent = section;
                        } else {
                            section.setHead(head);
                            parent = section;
                            section.setChild(section(datarow, headm, rowIndex, dim, parent));
                        }
                    }
            }
        }
        return sections;
    }
}
